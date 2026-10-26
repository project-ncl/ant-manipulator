package org.jboss.pnc.antmanipulator.report;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.commonjava.atlas.maven.ident.ref.SimpleProjectVersionRef;
import org.jboss.pnc.antmanipulator.gav.CorrelatedGav;
import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;
import org.jboss.pnc.mavenmanipulator.common.json.ModulesItem;
import org.jboss.pnc.mavenmanipulator.common.json.PME;
import org.jboss.pnc.mavenmanipulator.common.util.JSONUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds {@code alignmentReport.json} in PME's own JSON shape — the {@code org.jboss.pnc.mavenmanipulator.common.json}
 * POJOs, reused verbatim from {@code pom-manipulation-common-lite} — so PNC/Repour can consume Ant Manipulator's
 * output exactly like PME's and GME's: a diff of the groupId:artifactId:version changes actually computed, not
 * our own richer pre-alignment discovery report (see {@link AlignmentReport} for that).
 *
 * <p>
 * Unlike a Maven reactor, an Ant build has no single canonical root GAV. {@code executionRoot} — which PME/Repour
 * use to compute "the project's" GAV change — is taken from whichever changed artifact has a finding sourced
 * directly from the root build file, falling back to the first changed artifact (by groupId:artifactId order) if
 * none is declared there.
 */
public final class PmeAlignmentReport {

    private static final Logger logger = LoggerFactory.getLogger(PmeAlignmentReport.class);

    private PmeAlignmentReport() {
    }

    /**
     * @param rootBuildFile the build file passed to {@code -f}, used to pick the {@code executionRoot} artifact
     * @param correlated every artifact this run discovered, correlated by groupId:artifactId
     * @param reconciler resolves a tokenised version to its concrete value(s)
     * @param versionRewrites base version literal -&gt; computed publish version, as applied by the rewrite step
     * @return the populated report; {@code getModules()} is empty when nothing was realigned
     */
    public static PME build(
            File rootBuildFile,
            GavCorrelator.Result correlated,
            VersionReconciler reconciler,
            Map<String, String> versionRewrites) {
        return build(rootBuildFile, correlated, reconciler, versionRewrites, false);
    }

    /**
     * @param rootBuildFile the build file passed to {@code -f}, used to pick the {@code executionRoot} artifact
     * @param correlated every artifact this run discovered, correlated by groupId:artifactId
     * @param reconciler resolves a tokenised version to its concrete value(s)
     * @param versionRewrites base version literal -&gt; computed publish version, as applied by the rewrite step
     * @param includeUnchanged when {@code true}, also list artifacts whose version did not change — each as a
     *        module whose {@code originalGAV} equals its new GAV — so {@code reportJsonUnchanged=true} produces a
     *        useful report (executionRoot + every resolvable artifact) rather than an effectively empty one
     * @return the populated report; {@code getModules()} is empty only when nothing was realigned and, if
     *         {@code includeUnchanged} is {@code true}, no artifact had a resolvable concrete version
     */
    public static PME build(
            File rootBuildFile,
            GavCorrelator.Result correlated,
            VersionReconciler reconciler,
            Map<String, String> versionRewrites,
            boolean includeUnchanged) {

        PME report = new PME();
        ModulesItem executionRootModule = null;

        for (CorrelatedGav artifact : correlated.getArtifacts()) {
            String originalVersion;
            String newVersion;

            Realignment realignment = realignmentOf(artifact, reconciler, versionRewrites);
            if (realignment != null) {
                originalVersion = realignment.originalVersion;
                newVersion = realignment.newVersion;
            } else if (includeUnchanged) {
                originalVersion = resolvedVersion(artifact, reconciler);
                if (originalVersion == null) {
                    logger.debug(
                            "Skipping {}:{} from unchanged report: no resolvable concrete version",
                            artifact.getGroupId(),
                            artifact.getArtifactId());
                    continue;
                }
                newVersion = originalVersion; // unchanged: new GAV == original GAV
            } else {
                continue;
            }

            ModulesItem module = new ModulesItem();
            module.getGav()
                    .setOriginalGAV(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + originalVersion);
            module.getGav()
                    .setPVR(new SimpleProjectVersionRef(artifact.getGroupId(), artifact.getArtifactId(), newVersion));
            report.getModules().add(module);

            if (executionRootModule == null && isDeclaredIn(artifact, rootBuildFile)) {
                executionRootModule = module;
            }
        }

        if (executionRootModule == null && !report.getModules().isEmpty()) {
            executionRootModule = report.getModules().get(0);
        }
        if (executionRootModule != null) {
            report.getGav().setOriginalGAV(executionRootModule.getGav().getOriginalGAV());
            report.getGav().setPVR(executionRootModule.getGav().getPVR());
        }

        return report;
    }

    /** Write {@code report} to {@code file} using PME's own serialisation (pretty-printed, nulls/empties omitted). */
    public static File writeTo(PME report, File file) throws IOException {
        // UTF-8 explicitly (not JVM default charset) so non-ASCII coordinates serialise to the same bytes on
        // every platform, matching the discovery report and keeping the output parseable by PNC/Repour.
        Files.write(file.toPath(), JSONUtils.jsonToString(report).getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static final class Realignment {
        private final String originalVersion;
        private final String newVersion;

        private Realignment(String originalVersion, String newVersion) {
            this.originalVersion = originalVersion;
            this.newVersion = newVersion;
        }
    }

    /**
     * Whether, and how, this artifact was realigned. Its primary version's concrete candidates are either
     * itself (already concrete) or every value a tokenised version reconciles to — a tokenised version can
     * reconcile <em>ambiguously</em> (e.g. a release vs. its {@code -SNAPSHOT} variant both matching the same
     * filter token), which is fine as long as every candidate was realigned to the <em>same</em> new version;
     * if they diverge we can't confidently report a single before-value, so the artifact is skipped, mirroring
     * PME's refusal to guess.
     */
    private static Realignment realignmentOf(
            CorrelatedGav artifact,
            VersionReconciler reconciler,
            Map<String, String> versionRewrites) {
        Set<String> candidates = concreteCandidates(artifact, reconciler);
        if (candidates.isEmpty()) {
            return null; // unresolved
        }

        Set<String> newVersions = new LinkedHashSet<>();
        for (String candidate : candidates) {
            String newVersion = versionRewrites.get(candidate);
            if (newVersion != null) {
                newVersions.add(newVersion);
            }
        }
        if (newVersions.size() != 1) {
            return null; // not realigned, or candidates diverge on the realigned value
        }

        String newVersion = newVersions.iterator().next();
        String originalVersion = representativeVersion(candidates);
        return newVersion.equals(originalVersion) ? null : new Realignment(originalVersion, newVersion);
    }

    /**
     * The artifact's single representative concrete version — the value reported as {@code originalGAV}, shared by
     * both the realigned and the unchanged paths — or {@code null} when it has no resolvable concrete version.
     */
    private static String resolvedVersion(CorrelatedGav artifact, VersionReconciler reconciler) {
        Set<String> candidates = concreteCandidates(artifact, reconciler);
        return candidates.isEmpty() ? null : representativeVersion(candidates);
    }

    /**
     * The concrete value(s) an artifact's primary version resolves to: itself when already concrete, or every value
     * a tokenised version reconciles to. Empty when there is no primary version or it reconciles to nothing.
     */
    private static Set<String> concreteCandidates(CorrelatedGav artifact, VersionReconciler reconciler) {
        String primary = artifact.primaryVersion();
        if (primary == null) {
            return Collections.emptySet();
        }
        return artifact.tokenizedVersions().contains(primary)
                ? reconciler.reconcile(primary).getResolved()
                : Collections.singleton(primary);
    }

    /**
     * Deterministic, and prefers a release-looking candidate over e.g. its "-SNAPSHOT" sibling, since a release
     * version string is always a lexicographic prefix (and so the minimum) of that sibling.
     */
    private static String representativeVersion(Set<String> candidates) {
        return new TreeSet<>(candidates).first();
    }

    private static boolean isDeclaredIn(CorrelatedGav artifact, File rootBuildFile) {
        File canonicalRoot = canonical(rootBuildFile);
        return artifact.getFindings()
                .stream()
                .map(ResolvedGav::getSourceFile)
                .filter(Objects::nonNull)
                .map(PmeAlignmentReport::canonical)
                .anyMatch(f -> f.equals(canonicalRoot));
    }

    private static File canonical(File f) {
        try {
            return f.getCanonicalFile();
        } catch (IOException e) {
            // Falling back to the absolute (non-canonicalised) path may make isDeclaredIn() miss the root
            // build file and so pick a different executionRoot, so leave a trace of why.
            logger.debug("Could not canonicalise {}; falling back to absolute path: {}", f, e.getMessage());
            return f.getAbsoluteFile();
        }
    }
}
