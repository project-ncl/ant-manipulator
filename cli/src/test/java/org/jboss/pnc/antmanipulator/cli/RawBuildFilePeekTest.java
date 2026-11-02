package org.jboss.pnc.antmanipulator.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.jboss.pnc.antmanipulator.cli.RawBuildFilePeek.Kind;
import org.jboss.pnc.antmanipulator.cli.RawBuildFilePeek.Reference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawBuildFilePeekTest {

    private static File write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    @Test
    void peeksProjectAttributes(@TempDir Path dir) throws Exception {
        File build = write(dir, "build.xml", "<project name=\"my-proj\" default=\"compile\" basedir=\".\"/>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getProjectName()).isEqualTo("my-proj");
        assertThat(peek.getDefaultTarget()).isEqualTo("compile");
        assertThat(peek.getDeclaredBasedir()).isEqualTo(".");
        assertThat(peek.getBuildFile()).isEqualTo(build.getCanonicalFile());
    }

    @Test
    void peeksImportAndInclude(@TempDir Path dir) throws Exception {
        File imp = write(dir, "imported.xml", "<project/>");
        File inc = write(dir, "included.xml", "<project/>");
        File build = write(
                dir,
                "build.xml",
                "<project><import file=\"imported.xml\"/><include file=\"included.xml\"/></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(2);
        Reference r1 = peek.getReferences().get(0);
        assertThat(r1.getKind()).isEqualTo(Kind.IMPORT);
        assertThat(r1.getResolved()).isEqualTo(imp.getCanonicalFile());

        Reference r2 = peek.getReferences().get(1);
        assertThat(r2.getKind()).isEqualTo(Kind.INCLUDE);
        assertThat(r2.getResolved()).isEqualTo(inc.getCanonicalFile());
    }

    @Test
    void peeksAntTasks(@TempDir Path dir) throws Exception {
        Path sub = dir.resolve("sub");
        File subBuild = write(sub, "build.xml", "<project/>");
        File customBuild = write(sub, "custom.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\">"
                        + "<ant dir=\"sub\"/>"
                        + "<ant dir=\"sub\" antfile=\"custom.xml\"/>"
                        + "<ant antfile=\"sub/build.xml\"/>"
                        + "</target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(3);
        assertThat(peek.getReferences().get(0).getKind()).isEqualTo(Kind.ANT);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(subBuild.getCanonicalFile());

        assertThat(peek.getReferences().get(1).getKind()).isEqualTo(Kind.ANT);
        assertThat(peek.getReferences().get(1).getResolved()).isEqualTo(customBuild.getCanonicalFile());

        assertThat(peek.getReferences().get(2).getKind()).isEqualTo(Kind.ANT);
        assertThat(peek.getReferences().get(2).getResolved()).isEqualTo(subBuild.getCanonicalFile());
    }

    @Test
    void peeksSubantWithFilesetAndDirset(@TempDir Path dir) throws Exception {
        Path modA = dir.resolve("modA");
        File buildA = write(modA, "build.xml", "<project/>");
        Path modB = dir.resolve("modB");
        File buildB = write(modB, "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\">"
                        + "<subant><fileset dir=\".\" includes=\"modA/build.xml\"/></subant>"
                        + "<subant antfile=\"build.xml\"><dirset dir=\".\" includes=\"modB\"/></subant>"
                        + "</target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(2);
        assertThat(peek.getReferences().get(0).getKind()).isEqualTo(Kind.SUBANT);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(buildA.getCanonicalFile());

        assertThat(peek.getReferences().get(1).getKind()).isEqualTo(Kind.SUBANT);
        assertThat(peek.getReferences().get(1).getResolved()).isEqualTo(buildB.getCanonicalFile());
    }

    @Test
    void peeksPropertiesAndExpandsInPaths(@TempDir Path dir) throws Exception {
        Path core = dir.resolve("core");
        File coreBuild = write(core, "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><property name=\"sub\" value=\"core\"/>"
                        + "<target name=\"t\"><ant antfile=\"${sub}/build.xml\"/></target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        Reference ref = peek.getReferences().get(0);
        assertThat(ref.getKind()).isEqualTo(Kind.ANT);
        assertThat(ref.getResolved()).isEqualTo(coreBuild.getCanonicalFile());
    }

    @Test
    void peeksWithInitialPropertiesPassedIn(@TempDir Path dir) throws Exception {
        Path core = dir.resolve("core");
        File coreBuild = write(core, "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\"><ant antfile=\"${passed.dir}/build.xml\"/></target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build, Map.of("passed.dir", "core"));

        assertThat(peek.getReferences()).hasSize(1);
        Reference ref = peek.getReferences().get(0);
        assertThat(ref.getResolved()).isEqualTo(coreBuild.getCanonicalFile());
    }

    @Test
    void recordsUnresolvedPropertiesAsNullTarget(@TempDir Path dir) throws Exception {
        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\"><ant antfile=\"${unknown}/build.xml\"/></target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        Reference ref = peek.getReferences().get(0);
        assertThat(ref.getResolved()).isNull();
        assertThat(ref.getRawPath()).isEqualTo("${unknown}/build.xml");
    }

    // ---- subant fileset/dirset edge cases ----

    @Test
    void subantFilesetWithNonExistentDirFallsBackToApproximateReference(@TempDir Path dir) throws Exception {
        // The fileset's own dir doesn't exist yet (e.g. a module not yet checked out), so it resolves to
        // zero matches; this must still fall back to the old guaranteed approximate reference rather than
        // silently contributing nothing at all.
        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\">"
                        + "<subant><fileset dir=\"modules\" includes=\"*/build.xml\"/></subant>"
                        + "</target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        Reference ref = peek.getReferences().get(0);
        assertThat(ref.getKind()).isEqualTo(Kind.SUBANT);
        assertThat(ref.getResolved()).isEqualTo(dir.resolve("build.xml").toFile().getCanonicalFile());
    }

    @Test
    void subantFilesetWithNoMatchesFallsBackToApproximateReference(@TempDir Path dir) throws Exception {
        // The fileset's dir exists but the includes pattern matches nothing.
        Files.createDirectories(dir.resolve("modules"));
        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\">"
                        + "<subant><fileset dir=\"modules\" includes=\"nomatch/*.xml\"/></subant>"
                        + "</target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        assertThat(peek.getReferences().get(0).getResolved())
                .isEqualTo(dir.resolve("build.xml").toFile().getCanonicalFile());
    }

    @Test
    void dirsetAppliesDefaultExcludesLikeRealAnt(@TempDir Path dir) throws Exception {
        Path modA = dir.resolve("modA");
        write(modA, "build.xml", "<project/>");
        File gitBuild = write(dir.resolve(".git"), "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\">"
                        + "<subant><dirset dir=\".\" includes=\"**\"/></subant>"
                        + "</target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences())
                .extracting(Reference::getResolved)
                .doesNotContain(gitBuild.getCanonicalFile());
    }

    // ---- property expansion and conditions ----

    @Test
    void expandsManySequentialPropertiesInOnePass(@TempDir Path dir) throws Exception {
        // 11 distinct, sequentially-concatenated properties: the old one-token-per-depth-tick loop
        // exhausted its depth<10 cap before reaching the 11th.
        String[] names = { "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9", "p10", "p11" };
        String[] values = { "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k" };
        StringBuilder propsXml = new StringBuilder();
        StringBuilder dirName = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            propsXml.append("<property name=\"")
                    .append(names[i])
                    .append("\" value=\"")
                    .append(values[i])
                    .append("\"/>");
            dirName.append("${").append(names[i]).append("}");
        }
        File targetBuild = write(dir.resolve("abcdefghijk"), "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project>" + propsXml + "<target name=\"t\"><ant antfile=\"" + dirName
                        + "/build.xml\"/></target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(targetBuild.getCanonicalFile());
    }

    @Test
    void propertyInTargetWithUnmetIfConditionIsSkipped(@TempDir Path dir) throws Exception {
        File rightBuild = write(dir.resolve("right"), "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project>"
                        + "<target name=\"t1\" if=\"profileA\"><property name=\"subdir\" value=\"wrong\"/></target>"
                        + "<target name=\"t2\"><property name=\"subdir\" value=\"right\"/></target>"
                        + "<target name=\"main\"><ant antfile=\"${subdir}/build.xml\"/></target>"
                        + "</project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(rightBuild.getCanonicalFile());
    }

    @Test
    void propertyInTargetWithMetUnlessConditionIsSkipped(@TempDir Path dir) throws Exception {
        File rightBuild = write(dir.resolve("right"), "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><property name=\"skipIt\" value=\"true\"/>"
                        + "<target name=\"t1\" unless=\"skipIt\"><property name=\"subdir\" value=\"wrong\"/></target>"
                        + "<target name=\"t2\"><property name=\"subdir\" value=\"right\"/></target>"
                        + "<target name=\"main\"><ant antfile=\"${subdir}/build.xml\"/></target>"
                        + "</project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(rightBuild.getCanonicalFile());
    }

    @Test
    void propertyInTargetWithMetIfConditionIsApplied(@TempDir Path dir) throws Exception {
        File rightBuild = write(dir.resolve("right"), "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><property name=\"profileA\" value=\"true\"/>"
                        + "<target name=\"t1\" if=\"profileA\"><property name=\"subdir\" value=\"right\"/></target>"
                        + "<target name=\"main\"><ant antfile=\"${subdir}/build.xml\"/></target>"
                        + "</project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(rightBuild.getCanonicalFile());
    }

    // ---- buildpath tokenization ----

    @Test
    void buildpathDoesNotSplitOnCommaOrWhitespace(@TempDir Path dir) throws Exception {
        // Ant's real path tokenizer only splits on ':'/';' - never on a comma or whitespace, unlike the
        // old regex split here.
        File weirdBuild = write(dir.resolve("dir,with,commas and spaces"), "build.xml", "<project/>");

        File build = write(
                dir,
                "build.xml",
                "<project><target name=\"t\">"
                        + "<subant buildpath=\"dir,with,commas and spaces\"/>"
                        + "</target></project>");

        RawBuildFilePeek peek = RawBuildFilePeek.peek(build);

        assertThat(peek.getReferences()).hasSize(1);
        assertThat(peek.getReferences().get(0).getResolved()).isEqualTo(weirdBuild.getCanonicalFile());
    }
}
