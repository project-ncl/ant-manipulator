package org.jboss.pnc.antmanipulator.cli;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

import org.commonjava.atlas.maven.ident.ref.ProjectVersionRef;
import org.commonjava.atlas.maven.ident.ref.SimpleProjectVersionRef;
import org.jboss.pnc.antmanipulator.align.VersionIncrementer;
import org.jboss.pnc.antmanipulator.gav.CorrelatedGav;
import org.jboss.pnc.antmanipulator.gav.GavCorrelator;
import org.jboss.pnc.antmanipulator.gav.GavResolver;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav;
import org.jboss.pnc.antmanipulator.gav.VersionReconciler;
import org.jboss.pnc.antmanipulator.gav.VersionRewriter;
import org.jboss.pnc.antmanipulator.groovy.GroovyScriptRunner;
import org.jboss.pnc.antmanipulator.groovy.InvocationStage;
import org.jboss.pnc.antmanipulator.report.AlignmentReport;
import org.jboss.pnc.antmanipulator.report.PmeAlignmentReport;
import org.jboss.pnc.mavenmanipulator.common.exception.ManipulationException;
import org.jboss.pnc.mavenmanipulator.common.json.PME;
import org.jboss.pnc.mavenmanipulator.common.util.ManifestUtils;
import org.jboss.pnc.mavenmanipulator.io.rest.DefaultTranslator;
import org.jboss.pnc.mavenmanipulator.io.rest.RestException;
import org.jboss.pnc.mavenmanipulator.io.rest.Translator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Command-line entry point for the Ant manipulator.
 *
 * <p>
 * Modelled on {@code org.jboss.pnc.mavenmanipulator.cli.Cli} from pom-manipulation-ext: a picocli
 * command whose {@link #call()} is the real body, invoked from {@link #main(String[])}. The key option is
 * {@code -f/--file}, which points at the Ant build file to operate against (the equivalent of PME's
 * {@code pom.xml} execution root).
 */
@Command(
        name = "AME",
        description = "CLI to run the Ant Manipulation Extension",
        mixinStandardHelpOptions = true, // adds --help and --version
        exitCodeOnInvalidInput = 10,
        versionProvider = ManifestVersionProvider.class)
public class Cli implements Callable<Integer> {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    // -D property keys, matching pom-manipulation-ext (RESTState/VersioningState) and gradle-manipulator
    // verbatim so the same argument string is portable across the three tools.
    static final String PROP_REST_URL = "restURL";
    static final String PROP_REST_MODE = "restMode";
    static final String PROP_REST_BREW_PULL = "restBrewPullActive";
    static final String PROP_REST_CONNECTION_TIMEOUT = "restConnectionTimeout";
    static final String PROP_REST_SOCKET_TIMEOUT = "restSocketTimeout";
    static final String PROP_REST_MAX_SIZE = "restMaxSize";
    static final String PROP_REST_MIN_SIZE = "restMinSize";
    static final String PROP_REST_HEADERS = "restHeaders";
    static final String PROP_REST_RETRY_DURATION = "restRetryDuration";
    static final String PROP_VERSION_INCREMENTAL_SUFFIX = "versionIncrementalSuffix";
    /**
     * PME's {@code versionOverride}: the starting version used for every discovered coordinate instead of the
     * version found in the build files. Any {@code -SNAPSHOT} is removed and the result OSGi-normalised, and the
     * rebuild suffix (if any) is still applied afterwards.
     */
    static final String PROP_VERSION_OVERRIDE = "versionOverride";
    static final String PROP_VERSION_INCREMENTAL_SUFFIX_PADDING = "versionIncrementalSuffixPadding";
    /** PME's property, now governing the PME-shaped {@code alignmentReport.json} (see {@link PmeAlignmentReport}). */
    static final String PROP_REPORT_JSON = "reportJSONOutputFile";
    /** PME's property: write {@code reportJSONOutputFile} even when nothing was realigned. */
    static final String PROP_REPORT_JSON_UNCHANGED = "reportJsonUnchanged";
    /** antalignment-specific (no PME equivalent): our own richer, pre-alignment discovery report. */
    static final String PROP_DISCOVERY_REPORT_JSON = "discoveryReportJSONOutputFile";
    /** antalignment-specific (no PME equivalent): dev markers stripped like {@code -SNAPSHOT}. */
    static final String PROP_VERSION_SUFFIX_STRIP = "versionSuffixStrip";
    /**
     * PME's {@code versionModification} (default {@code true}). When {@code false}, the project's own version is
     * left untouched: no DA lookup, no computed suffix, no rewrite — the build publishes under its declared
     * version. PNC sets this for build categories meant to publish under the upstream version, pairing it with a
     * sentinel {@code versionIncrementalSuffix} that must never be applied. PME honours the flag, so AME must
     * too; otherwise that sentinel suffix leaks onto the artifacts.
     */
    static final String PROP_VERSION_MODIFICATION = "versionModification";

    /**
     * The build file to operate against. Defaults to {@code build.xml} in the current directory,
     * mirroring PME's default of {@code pom.xml}.
     */
    @SuppressWarnings("FieldMayBeFinal")
    @Option(names = { "-f", "--file" }, description = "Ant build file (default: ./build.xml)")
    private File target = new File(System.getProperty("user.dir"), "build.xml");

    @Option(names = { "-d", "--debug" }, description = "Enable debug logging")
    private boolean debug;

    /**
     * All configuration, PME/GME-style ({@code -Dkey=value}). This single bag carries both:
     * <ul>
     * <li>the alignment injection keys ({@code -Dalignment.groupId}, {@code -Dalignment.artifactId},
     * {@code -Dalignment.version}) that let you add coordinates a project never declares (e.g. Xalan);</li>
     * <li>the REST/DA and version-calculation keys defined by pom-manipulation-ext and gradle-manipulator
     * ({@code restURL}, {@code restMode}, {@code restHeaders}, {@code versionIncrementalSuffix},
     * {@code versionOverride}, …).</li>
     * </ul>
     * Using the exact PME/GME key names means the same argument string works across all three tools; the
     * recognised keys are the {@code PROP_*} constants below.
     */
    @Option(
            names = "-D",
            mapFallbackValue = "true",
            description = "Config properties, e.g. -DrestURL=... -DrestMode=PERSISTENT -DversionIncrementalSuffix=redhat")
    private final Properties properties = new Properties();

    /**
     * Whether to only <em>preview</em> the rewrite rather than apply it. Off by default: like PME/GME, the
     * computed publish versions are written back into the build files in place (PME's
     * {@code PomIO.rewritePOMs} analogue; no backups — this assumes the build files are under version
     * control). With {@code --preview} the rewrite step just logs the edits it would make and touches
     * nothing. PME/GME have no such flag (they always write), so this stays a flag rather than a
     * {@code -D} property.
     */
    @Option(
            names = { "--preview" },
            description = "Only show the edits that would be made; do not modify any files")
    private boolean preview;

    public static void main(String[] args) {
        System.exit(new Cli().run(args));
    }

    /**
     * Public so external tools can invoke the CLI programmatically.
     *
     * @param args command-line arguments
     * @return the process exit code
     */
    public int run(String[] args) {
        CommandLine cl = new CommandLine(this);
        cl.setUsageHelpAutoWidth(true);
        return cl.execute(args);
    }

    @Override
    public Integer call() {
        if (debug) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
                    .setLevel(ch.qos.logback.classic.Level.DEBUG);
        }

        final File buildFile = target.getAbsoluteFile();

        logger.info(
                "Running Ant Manipulation {} against Ant build file: {}",
                ManifestUtils.getManifestInformation(Cli.class),
                buildFile);

        if (!buildFile.exists()) {
            logger.error("Manipulation engine disabled. Build file {} cannot be found.", buildFile);
            return 10;
        }
        if (buildFile.isDirectory()) {
            logger.error("Target {} is a directory, not an Ant build file.", buildFile);
            return 10;
        }

        // Versioning is opt-in, as in PME's VersioningState.isEnabled(): the project version is only modified
        // when versionModification is not false AND a versioning option is configured. There is no default suffix.
        final boolean versionModification = boolProp(PROP_VERSION_MODIFICATION, true) && isVersioningRequested();

        // An empty versionOverride is rejected, like an empty versionIncrementalSuffix (a deliberate deviation from
        // PME, which treats it as unset in RESTCollector but as enabled in VersioningState.isEnabled()). It is
        // checked before any file is read or modified, and ignored when versionModification=false.
        if (versionModification && isBlank(properties.getProperty(PROP_VERSION_OVERRIDE))
                && properties.getProperty(PROP_VERSION_OVERRIDE) != null) {
            logger.error(
                    "Invalid {}: the value must not be empty. Provide a version (e.g. -D{}=1.2.0.Final) or omit the "
                            + "property.",
                    PROP_VERSION_OVERRIDE,
                    PROP_VERSION_OVERRIDE);
            return 10;
        }

        // Groovy scripts (-DgroovyScripts=...), as in PME/GME: FIRST-stage scripts run before anything is read,
        // so they may edit build files on disk; LAST-stage scripts run after the versions are rewritten.
        final String restUrl = properties.getProperty(PROP_REST_URL);
        final Translator translator;
        try {
            translator = restUrl == null || restUrl.trim().isEmpty() ? null : createTranslator(restUrl);
        } catch (RuntimeException e) {
            logger.error("Invalid REST configuration: {}", e.getMessage(), e);
            return 10;
        }
        final GroovyScriptRunner groovy = new GroovyScriptRunner(
                properties.getProperty(GroovyScriptRunner.PROP_GROOVY_SCRIPTS),
                buildFile,
                properties,
                translator);
        if (!runGroovyStage(groovy, InvocationStage.FIRST, null)) {
            return 10;
        }

        // Step 1 (the analogue of PME's PomIO.peekAtPomHierarchy): crawl the build-file hierarchy so
        // we know every file we may need to manipulate before touching anything. Also tells Step 2 which
        // build.xml files Ant actually reaches from here, so e.g. an abandoned sandbox proposal bundled
        // alongside the real sources under its own unreferenced build.xml (GitHub issue #44) isn't
        // treated as part of this build.
        final BuildFileScanner.ScanResult scan;
        try {
            scan = new BuildFileScanner().crawl(buildFile, properties);

            logger.info("Discovered {} build file(s) in the hierarchy:", scan.getPeeks().size());
            for (RawBuildFilePeek peek : scan.getPeeks()) {
                logger.info(
                        "    {} [{}]",
                        peek.getBuildFile(),
                        peek.getProjectName() == null ? "<unnamed>" : peek.getProjectName());
                for (RawBuildFilePeek.Reference ref : peek.getReferences()) {
                    logger.info("        {}", ref);
                }
            }
            if (!scan.getOutsideRootTree().isEmpty()) {
                logger.warn(
                        "{} build file(s) live outside the root directory tree:",
                        scan.getOutsideRootTree().size());
                scan.getOutsideRootTree().forEach(f -> logger.warn("    ! {}", f));
            }
            if (!scan.getMissing().isEmpty()) {
                logger.warn("{} reference(s) point to non-existent files.", scan.getMissing().size());
            }
            if (!scan.getUnresolved().isEmpty()) {
                logger.warn(
                        "{} reference(s) could not be resolved statically (contain ${{...}}).",
                        scan.getUnresolved().size());
            }
        } catch (Exception e) {
            logger.error("Failed to crawl build file hierarchy from {}: {}", buildFile, e.getMessage(), e);
            return 10;
        }

        // Step 2 (the analogue of reading GAV off the Maven model): Ant has no canonical place for
        // coordinates and one build may publish many, so we gather every candidate with provenance.
        try {
            Set<File> reachableBuildFiles = new LinkedHashSet<>();
            for (RawBuildFilePeek peek : scan.getPeeks()) {
                reachableBuildFiles.add(peek.getBuildFile());
            }
            List<ResolvedGav> gavs = new GavResolver().resolve(buildFile, properties, reachableBuildFiles);

            // Step 3: correlate findings by groupId:artifactId so per-artifact conflicts are explicit.
            GavCorrelator.Result correlated = new GavCorrelator().correlate(gavs);

            logger.info(
                    "Correlated {} coordinate candidate(s) into {} artifact(s):",
                    gavs.size(),
                    correlated.getArtifacts().size());
            for (CorrelatedGav artifact : correlated.getArtifacts()) {
                logger.info("    {}", artifact.summarize());
            }

            if (!isBlank(properties.getProperty(GavResolver.ALIGN_VERSION))
                    && (isBlank(properties.getProperty(GavResolver.ALIGN_GROUP_ID))
                            || isBlank(properties.getProperty(GavResolver.ALIGN_ARTIFACT_ID)))) {
                logger.warn(
                        "-D{} is ignored without both -D{} and -D{}.",
                        GavResolver.ALIGN_VERSION,
                        GavResolver.ALIGN_GROUP_ID,
                        GavResolver.ALIGN_ARTIFACT_ID);
            }

            List<CorrelatedGav> conflicts = new ArrayList<>();
            for (CorrelatedGav c : correlated.getConflicts()) {
                String injected = injectedVersionFor(c.getGroupId(), c.getArtifactId());
                if (injected == null) {
                    conflicts.add(c);
                } else {
                    // Not a conflict: the user chose the starting version for this coordinate.
                    Set<String> discovered = new LinkedHashSet<>(c.resolvedVersions());
                    discovered.remove(injected);
                    logger.info(
                            "{}: {} {} overrides discovered version(s) {}",
                            c.ga(),
                            GavResolver.ALIGN_VERSION,
                            injected,
                            discovered);
                }
            }
            if (!conflicts.isEmpty()) {
                logger.warn("{} artifact(s) have conflicting concrete versions:", conflicts.size());
                for (CorrelatedGav c : conflicts) {
                    logger.warn("    {} -> {}", c.ga(), c.resolvedVersions());
                    for (ResolvedGav f : c.getFindings()) {
                        logger.warn("        {}", f);
                    }
                }
            }

            List<ResolvedGav> projectVersions = correlated.getProjectVersionCandidates();
            if (!projectVersions.isEmpty()) {
                logger.info("Project-level version candidate(s) (no groupId:artifactId):");
                for (ResolvedGav pv : projectVersions) {
                    logger.info("    {}", pv);
                }
            }

            // Step 4: reconcile tokenised versions to concrete values by following
            // @TOKEN@ -> <filter> -> ${property} chains (the state Maven's model gives PME for free).
            VersionReconciler reconciler = VersionReconciler
                    .forTree(buildFile.getParentFile(), buildFile, properties, reachableBuildFiles);

            Set<String> tokenised = new LinkedHashSet<>();
            for (ResolvedGav g : gavs) {
                if (g.hasUnresolvedVersion()) {
                    tokenised.add(g.getVersion());
                }
            }
            if (!tokenised.isEmpty()) {
                logger.info("Reconciling {} tokenised version(s):", tokenised.size());
                for (String token : tokenised) {
                    logger.info("    {}", reconciler.reconcile(token).summarize());
                }
            }

            // Step 5: write our own richer, pre-alignment discovery report (every coordinate, its
            // provenance, conflicts, and reconciliation) — no PME/GME equivalent, so its own flag.
            String discoveryReportPath = properties.getProperty(PROP_DISCOVERY_REPORT_JSON);
            File discoveryOut = discoveryReportPath != null && !discoveryReportPath.trim().isEmpty()
                    ? new File(discoveryReportPath.trim())
                    : new File(buildFile.getParentFile(), "discovery-report.json");
            AlignmentReport.build(buildFile, gavs, correlated, reconciler).writeTo(discoveryOut);
            logger.info("Wrote discovery report to {}", discoveryOut.getAbsolutePath());

            // Step 6 (PME's Translator/DA round-trip + VersionCalculator): the discovered coordinates are
            // the project's OWN publishing coordinates, so aligning them means computing the version to
            // publish under — not swapping in an existing rebuild (that is dependency alignment). We ask DA's
            // lookup/maven/latest endpoint for the highest existing build, then increment its serial (or
            // synthesise <suffix>-00001 for a first build). The external source of truth decides the base,
            // exactly as PME delegates to the Dependency Analyzer.
            // Collect base version literal -> computed publish version, for the rewrite step.
            // When versionModification is false, or no versioning option is configured (see above), the project
            // version is left untouched — skip the DA lookup, the increment and the rewrite entirely, so the build
            // publishes under its declared version. (PME honours this; AME must too, else a sentinel suffix leaks
            // onto artifacts.)
            Map<String, String> versionRewrites = new LinkedHashMap<>();
            if (!versionModification) {
                logger.info(
                        "Version modification is disabled ({}=false, or no version modification option such as {} "
                                + "or {} is configured): leaving the project version unchanged "
                                + "(skipping DA lookup, version increment and rewrite).",
                        PROP_VERSION_MODIFICATION,
                        PROP_VERSION_INCREMENTAL_SUFFIX,
                        PROP_VERSION_OVERRIDE);
            } else {
                Map<String, String> computed = computeVersionRewrites(
                        correlated.getArtifacts(),
                        reconciler,
                        translator,
                        restUrl);
                if (computed == null) {
                    return 10; // the problem has already been logged
                }
                versionRewrites.putAll(computed);

                // Refuse before any file is touched when candidates of one artifact would be realigned to different
                // versions: the report could not list it, so the build would be rewritten with no report (silent).
                Map<String, Set<String>> divergent = PmeAlignmentReport
                        .divergentArtifacts(correlated, reconciler, versionRewrites);
                if (!divergent.isEmpty()) {
                    for (Map.Entry<String, Set<String>> e : divergent.entrySet()) {
                        logger.error(
                                "Cannot align {}: its candidate versions were realigned to different versions {}. "
                                        + "No build file was modified and no alignment report was written.",
                                e.getKey(),
                                e.getValue());
                    }
                    return 10;
                }

                // Step 7 (PME's PomIO.rewritePOMs analogue): apply the computed publish versions back to
                // the build files at their definition sites. Applies in place by default; --preview skips.
                rewriteVersions(buildFile.getParentFile(), versionRewrites, reconciler, reachableBuildFiles);
            }

            // Step 8 (PME's alignmentReport.json analogue): write a diff-shaped report of the GAV changes
            // actually computed above, in PME's own JSON shape, so PNC/Repour can consume Ant Manipulator's
            // output exactly like PME's and GME's.
            boolean reportJsonUnchanged = Boolean
                    .parseBoolean(properties.getProperty(PROP_REPORT_JSON_UNCHANGED, "false"));
            // With versionModification=false nothing was realigned, but PNC/Repour still needs the execution
            // root's (unchanged) coordinate to record and tag the build under its declared version. Passing
            // reportJsonUnchanged through lets buildUnchanged honour the flag too: when set it lists every
            // discovered artifact unchanged (not just the execution root), exactly as the realigned path does.
            PME pmeReport = versionModification
                    ? PmeAlignmentReport.build(buildFile, correlated, reconciler, versionRewrites, reportJsonUnchanged)
                    : PmeAlignmentReport.buildUnchanged(buildFile, correlated, reconciler, reportJsonUnchanged);
            if (!pmeReport.getModules().isEmpty() || reportJsonUnchanged) {
                String reportPath = properties.getProperty(PROP_REPORT_JSON);
                File out = reportPath != null && !reportPath.trim().isEmpty()
                        ? new File(reportPath.trim())
                        : new File(buildFile.getParentFile(), "alignmentReport.json");
                PmeAlignmentReport.writeTo(pmeReport, out);
                logger.info("Wrote alignment report to {}", out.getAbsolutePath());
            } else {
                logger.warn(
                        "alignmentReport.json not written: no artifact was realigned "
                                + "(use -D{}=true to write it regardless)",
                        PROP_REPORT_JSON_UNCHANGED);
            }

            // LAST-stage Groovy scripts see the final computed versions (and the rewritten files).
            if (!runGroovyStage(groovy, InvocationStage.LAST, versionRewrites)) {
                return 10;
            }
        } catch (Exception e) {
            logger.error("Failed to resolve GAV coordinates from {}: {}", buildFile, e.getMessage(), e);
            return 10;
        }

        return 0;
    }

    /** Runs one stage of Groovy scripts, logging and reporting failure rather than throwing. */
    private boolean runGroovyStage(
            GroovyScriptRunner groovy,
            InvocationStage stage,
            Map<String, String> alignedVersions) {
        try {
            groovy.run(stage, alignedVersions);
            return true;
        } catch (RuntimeException e) {
            logger.error("Groovy script failed: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Build the DA client from the {@code rest*} properties. */
    private Translator createTranslator(String restUrl) {
        return new DefaultTranslator(
                restUrl,
                intProp(PROP_REST_MAX_SIZE, -1),
                intProp(PROP_REST_MIN_SIZE, 1),
                boolProp(PROP_REST_BREW_PULL),
                stringProp(PROP_REST_MODE, "PERSISTENT"),
                parseRestHeaders(properties.getProperty(PROP_REST_HEADERS)),
                intProp(PROP_REST_CONNECTION_TIMEOUT, Translator.DEFAULT_CONNECTION_TIMEOUT_SEC),
                intProp(PROP_REST_SOCKET_TIMEOUT, Translator.DEFAULT_SOCKET_TIMEOUT_SEC),
                intProp(PROP_REST_RETRY_DURATION, Translator.RETRY_DURATION_SEC));
    }

    /**
     * One artifact to align: every concrete version literal it is declared under, and the optional
     * {@code base} version that replaces them as the starting point for the publish-version computation
     * ({@code alignment.version} for the injected coordinate, else {@code versionOverride}). When {@code base}
     * is {@code null} each literal is its own starting point, as before {@code versionOverride} existed.
     */
    private static final class AlignmentTarget {
        private final String groupId;
        private final String artifactId;
        private final Set<String> literals;
        private final String base;

        private AlignmentTarget(String groupId, String artifactId, Set<String> literals, String base) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.literals = literals;
            this.base = base;
        }

        /** The versions to start a computation (and a DA lookup) from. */
        private Set<String> startingVersions() {
            return base != null ? Collections.singleton(base) : literals;
        }

        /** The literals whose definition sites get the version computed from {@code startingVersion}. */
        private Set<String> rewriteKeysFor(String startingVersion) {
            return base != null ? literals : Collections.singleton(startingVersion);
        }
    }

    /**
     * The starting versions sharing one calculation base (D2). {@code refs} are the DA lookups for those starts,
     * {@code keys} the literals they rewrite to the group's single new version, and {@code firstStart} the start used
     * for a first build.
     */
    private static final class PublishGroup {
        private final String firstStart;
        private final Set<ProjectVersionRef> refs = new LinkedHashSet<>();
        private final Set<String> keys = new LinkedHashSet<>();

        private PublishGroup(String firstStart) {
            this.firstStart = firstStart;
        }

        private void add(ProjectVersionRef ref, Set<String> rewriteKeys) {
            refs.add(ref);
            keys.addAll(rewriteKeys);
        }
    }

    /**
     * Step 6: compute the version each aligned literal should be rewritten to (base literal -&gt; publish version).
     *
     * <ul>
     * <li>{@code versionOverride} only: the override is normalised ({@code -SNAPSHOT} removed, OSGi-ified) and
     * every literal of every artifact maps to it. No DA lookup, so no {@code restURL} is needed.</li>
     * <li>{@code versionIncrementalSuffix}: DA is asked for the latest build of each starting version (the
     * override, when set, else each declared literal), and the suffix serial is incremented from there.</li>
     * </ul>
     *
     * @return the rewrite map (possibly empty), or {@code null} if the run must fail; the reason is already logged
     */
    private Map<String, String> computeVersionRewrites(
            List<CorrelatedGav> artifacts,
            VersionReconciler reconciler,
            Translator translator,
            String restUrl) {
        final Map<String, String> rewrites = new LinkedHashMap<>();
        final String override = isBlank(properties.getProperty(PROP_VERSION_OVERRIDE))
                ? null
                : properties.getProperty(PROP_VERSION_OVERRIDE).trim();
        final boolean suffixConfigured = properties.getProperty(PROP_VERSION_INCREMENTAL_SUFFIX) != null;

        if (suffixConfigured && translator == null) {
            if (override != null) {
                // Publishing the override without the requested suffix would be a silent surprise.
                logger.error(
                        "{} is set together with {} but {} is not: the suffix cannot be applied without a DA lookup. "
                                + "Provide -D{}=<url>, or drop -D{}.",
                        PROP_VERSION_OVERRIDE,
                        PROP_VERSION_INCREMENTAL_SUFFIX,
                        PROP_REST_URL,
                        PROP_REST_URL,
                        PROP_VERSION_INCREMENTAL_SUFFIX);
                return null;
            }
            logger.warn(
                    "{} is set but {} is not: no DA lookup is possible, so the project version is left unchanged.",
                    PROP_VERSION_INCREMENTAL_SUFFIX,
                    PROP_REST_URL);
            return rewrites;
        }

        final List<AlignmentTarget> targets = alignmentTargets(artifacts, reconciler, override);
        if (targets.isEmpty()) {
            // The single decision point for "nothing to align", identical for suffix and override runs. Fail
            // eagerly (NCLSUP-1794): exiting 0 here would leave no alignmentReport.json for PNC/Reqour to read.
            logger.error(
                    "No concrete coordinates to align. Use -D{} and -D{} to supply a coordinate "
                            + "the project does not declare.",
                    GavResolver.ALIGN_GROUP_ID,
                    GavResolver.ALIGN_ARTIFACT_ID);
            return null;
        }

        if (override != null) {
            Set<String> replaced = new LinkedHashSet<>();
            for (AlignmentTarget t : targets) {
                if (override.equals(t.base)) {
                    replaced.addAll(t.literals);
                }
            }
            if (!replaced.isEmpty()) {
                logger.info("Overriding base version(s) {} with {}", replaced, override);
            }
        }

        if (!suffixConfigured) {
            // versionOverride alone: no suffix to compute, so no DA round-trip is needed.
            for (AlignmentTarget t : targets) {
                final String newVersion;
                try {
                    newVersion = VersionIncrementer.applyOverride(t.base);
                } catch (ManipulationException e) {
                    logger.error(
                            "Cannot compute a publish version for {}:{} from {}: {}",
                            t.groupId,
                            t.artifactId,
                            t.base,
                            e.getMessage());
                    return null;
                }
                logger.info("    {}:{}:{} -> {}", t.groupId, t.artifactId, t.base, newVersion);
                for (String literal : t.literals) {
                    putRewrite(rewrites, literal, newVersion);
                }
            }
            return rewrites;
        }

        // Build the incrementer before the DA round-trip so an unusable suffix fails before any lookup.
        // PME accepts an empty suffix (a bare counter, e.g. 1.2.0 -> 1.2.0.3), but VersionIncrementer
        // cannot produce or recognise that form, so it rejects it rather than emit malformed versions or
        // hide a likely mistake such as an empty templated value.
        final int padding = intProp(PROP_VERSION_INCREMENTAL_SUFFIX_PADDING, VersionIncrementer.DEFAULT_PADDING);
        final VersionIncrementer incrementer;
        try {
            incrementer = new VersionIncrementer(
                    properties.getProperty(PROP_VERSION_INCREMENTAL_SUFFIX),
                    padding,
                    false,
                    properties.getProperty(PROP_VERSION_SUFFIX_STRIP, VersionIncrementer.DEFAULT_DEV_MARKERS));
        } catch (IllegalArgumentException e) {
            logger.error(
                    "Invalid {}: {} Provide a value (e.g. -D{}=redhat) or omit the property.",
                    PROP_VERSION_INCREMENTAL_SUFFIX,
                    e.getMessage(),
                    PROP_VERSION_INCREMENTAL_SUFFIX);
            return null;
        }

        // When a version is target-dependent (a tokenised version reconciling ambiguously, e.g. a release vs a
        // -SNAPSHOT variant) every variant is looked up: the lookup is read-only, so asking about each and letting
        // the service report which it knows is more informative than dropping the coordinate. A coordinate Atlas
        // rejects aborts the run (see toRef).
        // Group every starting version by its calculation base, across all coordinates (PME's reactor sync): versions
        // sharing a base are published under one version, the highest existing DA build + 1. A group's refs are what DA
        // is asked; its keys are the literals rewritten to the group's version.
        final Map<String, PublishGroup> groups = new LinkedHashMap<>();
        for (AlignmentTarget t : targets) {
            for (String start : t.startingVersions()) {
                final String key;
                final ProjectVersionRef ref;
                try {
                    key = incrementer.baseOf(start);
                    ref = lookupRefOf(t, start);
                } catch (ManipulationException e) {
                    // No usable base version (e.g. a bare "SNAPSHOT"): abort rather than silently
                    // publish this coordinate under its old, unchanged version.
                    logger.error(
                            "Cannot compute a publish version for {}:{}:{}: {}",
                            t.groupId,
                            t.artifactId,
                            start,
                            e.getMessage());
                    return null;
                }
                groups.computeIfAbsent(key, k -> new PublishGroup(start)).add(ref, t.rewriteKeysFor(start));
            }
        }

        final Set<ProjectVersionRef> lookups = new LinkedHashSet<>();
        for (PublishGroup group : groups.values()) {
            lookups.addAll(group.refs);
        }
        logger.info("Aligning {} project coordinate(s) against {}", lookups.size(), restUrl);
        final Map<ProjectVersionRef, String> latest;
        try {
            latest = translator.lookupProjectVersions(new ArrayList<>(lookups));
        } catch (RestException e) {
            logger.error("DA lookup failed: {}", e.getMessage(), e);
            return null;
        }

        logger.info("Computed publish version(s) for {} coordinate(s):", lookups.size());
        for (PublishGroup group : groups.values()) {
            List<String> daLatests = new ArrayList<>();
            for (ProjectVersionRef ref : group.refs) {
                daLatests.add(latest.get(ref)); // null when DA has no prior build
            }
            VersionIncrementer.Result r;
            try {
                r = incrementer.nextVersionForGroup(group.firstStart, daLatests);
            } catch (ManipulationException e) {
                // No usable base version (e.g. a bare "SNAPSHOT"): abort rather than silently
                // publish this coordinate under its old, unchanged version.
                logger.error("Cannot compute a publish version from {}: {}", group.firstStart, e.getMessage());
                return null;
            }
            String synchronised = group.refs.size() > 1
                    ? ", synchronised " + group.refs.size() + " coordinate(s) to the highest existing build"
                    : "";
            logger.info("    {} -> {}{}", group.firstStart, r.summarize(), synchronised);
            for (String literal : group.keys) {
                putRewrite(rewrites, literal, r.getNewVersion());
            }
        }
        return rewrites;
    }

    /** Record {@code literal -> newVersion}; if the literal already maps elsewhere keep the first and flag it. */
    private void putRewrite(Map<String, String> rewrites, String literal, String newVersion) {
        String prior = rewrites.putIfAbsent(literal, newVersion);
        if (prior != null && !prior.equals(newVersion)) {
            // Same literal defines two coordinates that align differently; we can't rewrite
            // one literal to two values, so keep the first and flag it rather than guess.
            logger.warn(
                    "Version literal {} maps to both {} and {}; rewriting to {} only.",
                    literal,
                    prior,
                    newVersion,
                    prior);
        }
    }

    /**
     * The artifacts worth aligning: those with a groupId, an artifactId and at least one concrete version literal
     * (a tokenised version contributes whatever it reconciles to; an unresolved one contributes nothing). Each gets
     * its {@code base} version: {@code alignment.version} when it is the injected coordinate, else
     * {@code versionOverride}, else none.
     */
    private List<AlignmentTarget> alignmentTargets(
            List<CorrelatedGav> artifacts,
            VersionReconciler reconciler,
            String override) {
        List<AlignmentTarget> targets = new ArrayList<>();
        for (CorrelatedGav artifact : artifacts) {
            if (isBlank(artifact.getGroupId()) || isBlank(artifact.getArtifactId())) {
                continue; // can't be aligned
            }
            Set<String> literals = new LinkedHashSet<>();
            for (ResolvedGav f : artifact.getFindings()) {
                String version = f.getVersion();
                if (version == null) {
                    continue;
                }
                if (f.hasUnresolvedVersion()) {
                    literals.addAll(reconciler.reconcile(version).getResolved());
                } else {
                    literals.add(version);
                }
            }
            if (literals.isEmpty()) {
                continue;
            }

            String injected = injectedVersionFor(artifact.getGroupId(), artifact.getArtifactId());
            String base = injected != null ? injected : override;
            if (injected != null && override != null) {
                logger.debug(
                        "{}: {}={} takes precedence over {}={}",
                        artifact.ga(),
                        GavResolver.ALIGN_VERSION,
                        injected,
                        PROP_VERSION_OVERRIDE,
                        override);
            }
            targets.add(new AlignmentTarget(artifact.getGroupId(), artifact.getArtifactId(), literals, base));
        }
        return targets;
    }

    /**
     * The explicit {@code -Dalignment.version} when {@code groupId:artifactId} is the coordinate supplied through
     * {@code -Dalignment.groupId}/{@code -Dalignment.artifactId}, else {@code null}. A version borrowed from the
     * root build file (no explicit {@code alignment.version}) is not an override, so yields {@code null}.
     */
    private String injectedVersionFor(String groupId, String artifactId) {
        String version = stringProp(GavResolver.ALIGN_VERSION, null);
        if (version != null && groupId.equals(stringProp(GavResolver.ALIGN_GROUP_ID, null))
                && artifactId.equals(stringProp(GavResolver.ALIGN_ARTIFACT_ID, null))) {
            return version;
        }
        return null;
    }

    /**
     * Convert plain string coordinates to a {@link ProjectVersionRef}. Atlas may reject non-standard version
     * strings; as in PME (whose {@code InvalidRefException} also aborts the run with exit 10), an unusable
     * coordinate is treated as fatal rather than skipped. The wrapped exception names the offending
     * {@code groupId:artifactId:version}. Blank/null versions never reach here ({@code ResolvedGav}
     * normalises them to null and {@link #alignmentTargets} skips them), matching PME's "skip empty
     * version" handling.
     */
    static ProjectVersionRef toRef(String groupId, String artifactId, String version) {
        try {
            return new SimpleProjectVersionRef(groupId, artifactId, version);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot create ProjectVersionRef for " + groupId + ":" + artifactId + ":" + version,
                    e);
        }
    }

    /**
     * The DA lookup ref for a starting version. A version taken from the build files is normalised the way PME's
     * {@code RESTCollector} does ({@link VersionIncrementer#lookupVersionOf}), so {@code 1.9.11-SNAPSHOT} is looked up
     * as {@code 1.9.11}. A {@code base} (override or {@code alignment.version}) is sent raw, as PME does.
     *
     * @throws ManipulationException if a file-derived version has no usable base
     */
    private static ProjectVersionRef lookupRefOf(AlignmentTarget t, String start) throws ManipulationException {
        String version = t.base != null ? start : VersionIncrementer.lookupVersionOf(start);
        return toRef(t.groupId, t.artifactId, version);
    }

    /**
     * Locate the definition sites of each base version literal and apply the computed publish version there
     * (or, with {@code --preview}, just report them). Reports every planned edit so the user can verify that only
     * version-defining positions — not prose or unrelated properties — are being touched.
     */
    private void rewriteVersions(
            File rootDir,
            Map<String, String> versionRewrites,
            VersionReconciler reconciler,
            Set<File> reachableBuildFiles) throws java.io.IOException {
        if (versionRewrites.isEmpty()) {
            return;
        }
        List<VersionRewriter.Edit> edits = VersionRewriter
                .plan(rootDir, versionRewrites, reconciler, reachableBuildFiles);
        if (edits.isEmpty()) {
            logger.info(
                    "No rewritable version definition site found for the computed version(s); "
                            + "the project version may be composed at build time and cannot be rewritten statically.");
            return;
        }

        logger.info(
                "{} the following {} version definition site(s):",
                preview ? "Would rewrite (preview; omit --preview to apply)" : "Rewriting",
                edits.size());
        for (VersionRewriter.Edit e : edits) {
            logger.info("    {}", e.summarize());
        }

        if (!preview) {
            List<File> changed = VersionRewriter.apply(edits);
            logger.info("Wrote {} file(s):", changed.size());
            for (File f : changed) {
                logger.info("    {}", f);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * The analogue of the option check in PME's {@code VersioningState.isEnabled()}: whether any option that asks
     * for the project version to be modified is configured. Today that is {@code versionIncrementalSuffix} or
     * {@code versionOverride} (there is deliberately no default for either).
     */
    private boolean isVersioningRequested() {
        return properties.getProperty(PROP_VERSION_INCREMENTAL_SUFFIX) != null
                || properties.getProperty(PROP_VERSION_OVERRIDE) != null;
    }

    /**
     * Parses PME/GME's {@code restHeaders} value: comma-separated {@code name:value} pairs, each split on
     * its first {@code :} (so header values may themselves contain colons, e.g. {@code Bearer eyJ...}).
     * Returns an insertion-ordered map; blank/null yields an empty map.
     */
    static Map<String, String> parseRestHeaders(String value) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (value == null || value.trim().isEmpty()) {
            return headers;
        }
        for (String pair : value.split(",")) {
            String entry = pair.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon < 0) {
                headers.put(entry, "");
            } else {
                headers.put(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    /** {@code true} only when the property is present and parses truthy; unset stays {@code null}. */
    private Boolean boolProp(String key) {
        String v = properties.getProperty(key);
        return v == null ? null : Boolean.valueOf(v.trim());
    }

    /**
     * The property parsed as a boolean, or {@code fallback} only when the key is absent. Mirrors PME's
     * {@code Boolean.parseBoolean(userProperties.getProperty(key, String.valueOf(fallback)))}: a present-but-empty
     * value (e.g. {@code -DversionModification=}) is {@code ""}, which {@link Boolean#parseBoolean} treats as
     * {@code false} rather than falling back. Keeping this verbatim is what lets the same argument string stay
     * portable across PME/GME/AME.
     */
    private boolean boolProp(String key, boolean fallback) {
        return Boolean.parseBoolean(properties.getProperty(key, String.valueOf(fallback)));
    }

    /** The property parsed as an int, or {@code null} when unset. */
    private Integer intProp(String key) {
        String v = properties.getProperty(key);
        return v == null || v.trim().isEmpty() ? null : Integer.valueOf(v.trim());
    }

    /** The property parsed as an int, or {@code fallback} when unset/blank. */
    private int intProp(String key, int fallback) {
        Integer v = intProp(key);
        return v == null ? fallback : v;
    }

    /** The property as a String, or {@code fallback} when unset/blank. */
    private String stringProp(String key, String fallback) {
        String v = properties.getProperty(key);
        return (v == null || v.trim().isEmpty()) ? fallback : v.trim();
    }
}
