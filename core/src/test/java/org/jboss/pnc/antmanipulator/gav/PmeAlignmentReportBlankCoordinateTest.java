package org.jboss.pnc.antmanipulator.gav;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jboss.pnc.antmanipulator.gav.ResolvedGav.Confidence;
import org.jboss.pnc.antmanipulator.gav.ResolvedGav.SourceKind;
import org.jboss.pnc.antmanipulator.report.PmeAlignmentReport;
import org.jboss.pnc.mavenmanipulator.common.json.PME;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins {@link PmeAlignmentReport}'s defensive guard against a blank-but-non-null groupId/artifactId, which would
 * otherwise serialise to a malformed GAV (e.g. the leading-colon {@code :widget:1.0}) that PNC/Repour may fail to
 * parse.
 *
 * <p>
 * This test lives in the {@code gav} package — rather than beside the other {@code PmeAlignmentReport} tests — only
 * so it can reach the package-private {@link CorrelatedGav}/{@link GavCorrelator.Result} constructors: the normal
 * public path cannot produce a blank coordinate (a blank value is normalised to {@code null} by {@link ResolvedGav}
 * and then dropped by {@link GavCorrelator#correlate}), so a hand-built {@code CorrelatedGav} is the only way to
 * exercise the guard directly.
 */
class PmeAlignmentReportBlankCoordinateTest {

    @TempDir
    File tempDir;

    private VersionReconciler reconciler(File buildFile) {
        return VersionReconciler.forTree(tempDir, buildFile);
    }

    @Test
    void skipsBlankGroupIdWhenForced() {
        File buildFile = new File(tempDir, "build.xml");
        // A blank groupId with an otherwise resolvable concrete version: without the guard this would be listed
        // as an unchanged module with originalGAV ":widget:1.0".
        CorrelatedGav blank = new CorrelatedGav(
                "",
                "widget",
                List.of(
                        new ResolvedGav(
                                "ignored",
                                "widget",
                                "1.0",
                                buildFile,
                                "detail",
                                SourceKind.POM_TEMPLATE,
                                Confidence.HIGH)));
        GavCorrelator.Result correlated = new GavCorrelator.Result(List.of(blank), Collections.emptyList());

        PME report = PmeAlignmentReport.build(buildFile, correlated, reconciler(buildFile), Map.of(), true);

        assertThat(report.getModules()).isEmpty();
        assertThat(report.getGav().getGroupId()).isNull();
        assertThat(report.getGav().getOriginalGAV()).isNull();
    }
}
