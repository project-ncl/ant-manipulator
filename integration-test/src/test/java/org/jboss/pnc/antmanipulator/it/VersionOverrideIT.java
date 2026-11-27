package org.jboss.pnc.antmanipulator.it;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.pnc.antmanipulator.cli.Cli;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * End-to-end tests for {@code -DversionOverride} (PME parity, GitHub issue #40) and for
 * {@code -Dalignment.version} acting as the starting version when its coordinate is also declared by the build.
 *
 * <p>
 * Two kinds of project are used: the JUnit 4 fixture (the coordinate exists only through
 * {@code -Dalignment.groupId/artifactId} injection, its version coming from the root build file's own property),
 * and a tiny inline project whose {@code pom} template really declares {@code org.acme:widget}.
 */
class VersionOverrideIT {

    private static final String LOOKUP = "/lookup/maven/latest";

    @TempDir
    Path projectDir;

    private WireMockServer daServer;

    @AfterEach
    void stopDaServer() {
        if (daServer != null) {
            daServer.stop();
        }
    }

    // ---- JUnit 4 fixture: coordinate injected, declared version 4.8 ----

    @Test
    void versionOverrideAloneRewritesWithoutAnyDaLookup() throws IOException, URISyntaxException {
        File build = junitFixture();

        // No restURL and no suffix: the override is the whole answer, so no DA is needed.
        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DversionOverride=4.9.0.Final" });

        assertThat(exitCode).isEqualTo(0);
        assertThat(readFile(build))
                .contains("<property name=\"version-base\" value=\"4.9.0.Final\" />")
                .contains("<property name=\"version\" value=\"4.9.0.Final\" />")
                .contains("<filter token=\"version\" value=\"4.9.0.Final\" />");

        String report = readFile(projectDir.resolve("alignmentReport.json").toFile());
        assertThat(report).contains("\"originalGAV\" : \"junit:junit:4.8\"").contains("\"version\" : \"4.9.0.Final\"");
    }

    @Test
    void versionOverrideIsNormalisedLikePme() throws IOException, URISyntaxException {
        File build = junitFixture();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DversionOverride=4.9-SNAPSHOT" });

        assertThat(exitCode).isEqualTo(0);
        // -SNAPSHOT removed; a plain two-component version is not zero-filled.
        assertThat(readFile(build)).contains("<property name=\"version-base\" value=\"4.9\" />");
    }

    @Test
    void versionOverrideWithSuffixLooksUpTheOverrideAndAppliesTheSuffix() throws IOException, URISyntaxException {
        File build = junitFixture();
        daServer = stubFirstBuildDaServer();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DrestURL=" + daBaseUrl(),
                        "-DversionIncrementalSuffix=redhat",
                        "-DversionOverride=4.9.0.Final" });

        assertThat(exitCode).isEqualTo(0);
        // DA is asked about the override, never about the declared 4.8...
        daServer.verify(
                postRequestedFor(urlEqualTo(LOOKUP))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].version", equalTo("4.9.0.Final"))));
        daServer.verify(
                0,
                postRequestedFor(urlEqualTo(LOOKUP))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].version", equalTo("4.8"))));
        // ...and the declared 4.8 sites are rewritten to the override plus the suffix.
        assertThat(readFile(build)).contains("<property name=\"version-base\" value=\"4.9.0.Final-redhat-00001\" />")
                .doesNotContain("value=\"4.8\"");
    }

    @Test
    void versionOverrideIsIgnoredWhenVersionModificationIsFalse() throws IOException, URISyntaxException {
        File build = junitFixture();
        String before = readFile(build);
        daServer = stubFirstBuildDaServer();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DrestURL=" + daBaseUrl(),
                        "-DversionOverride=4.9.0.Final",
                        "-DversionModification=false" });

        assertThat(exitCode).isEqualTo(0);
        daServer.verify(0, postRequestedFor(urlEqualTo(LOOKUP)));
        assertThat(readFile(build)).isEqualTo(before);
    }

    @Test
    void blankVersionOverrideFailsFast() throws IOException, URISyntaxException {
        File build = junitFixture();
        String before = readFile(build);

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DversionOverride=" });

        assertThat(exitCode).isEqualTo(10);
        assertThat(readFile(build)).isEqualTo(before);
        assertThat(projectDir.resolve("alignmentReport.json")).doesNotExist();
    }

    @Test
    void blankVersionOverrideIsIgnoredWhenVersionModificationIsFalse() throws IOException, URISyntaxException {
        File build = junitFixture();
        String before = readFile(build);

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DversionOverride=",
                        "-DversionModification=false" });

        assertThat(exitCode).isEqualTo(0);
        assertThat(readFile(build)).isEqualTo(before);
    }

    @Test
    void versionOverrideWithSuffixButNoRestUrlFailsFast() throws IOException, URISyntaxException {
        File build = junitFixture();
        String before = readFile(build);

        // Publishing the override without the requested suffix would be a silent surprise.
        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-DversionIncrementalSuffix=redhat",
                        "-DversionOverride=4.9.0.Final" });

        assertThat(exitCode).isEqualTo(10);
        assertThat(readFile(build)).isEqualTo(before);
    }

    @Test
    void alignmentVersionWinsOverVersionOverrideForTheInjectedCoordinate() throws IOException, URISyntaxException {
        File build = junitFixture();
        daServer = stubFirstBuildDaServer();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=junit",
                        "-Dalignment.artifactId=junit",
                        "-Dalignment.version=4.7",
                        "-DrestURL=" + daBaseUrl(),
                        "-DversionIncrementalSuffix=redhat",
                        "-DversionOverride=4.9.0.Final" });

        assertThat(exitCode).isEqualTo(0);
        daServer.verify(
                postRequestedFor(urlEqualTo(LOOKUP))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].version", equalTo("4.7"))));
        daServer.verify(
                0,
                postRequestedFor(urlEqualTo(LOOKUP))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].version", equalTo("4.9.0.Final"))));
    }

    // ---- inline project whose pom template really declares org.acme:widget:1.0 ----

    @Test
    void alignmentVersionMatchingADiscoveredCoordinateActsAsTheStartingVersion() throws IOException {
        File build = widgetProject();
        daServer = stubFirstBuildDaServer();

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-Dalignment.groupId=org.acme",
                        "-Dalignment.artifactId=widget",
                        "-Dalignment.version=2.0",
                        "-DrestURL=" + daBaseUrl(),
                        "-DversionIncrementalSuffix=redhat" });

        assertThat(exitCode).isEqualTo(0);
        // DA is asked about the user's 2.0, not the declared 1.0...
        daServer.verify(
                postRequestedFor(urlEqualTo(LOOKUP))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].version", equalTo("2.0"))));
        daServer.verify(
                0,
                postRequestedFor(urlEqualTo(LOOKUP))
                        .withRequestBody(matchingJsonPath("$.artifacts[0].version", equalTo("1.0"))));
        // ...yet the declared 1.0 definition site is what gets rewritten.
        assertThat(readFile(build)).contains("<property name=\"widget.version\" value=\"2.0.0.redhat-00001\" />");

        // The report's "original" is what the files declared, never the injected version.
        String report = readFile(projectDir.resolve("alignmentReport.json").toFile());
        assertThat(report).contains("\"originalGAV\" : \"org.acme:widget:1.0\"")
                .contains("\"version\" : \"2.0.0.redhat-00001\"");
    }

    @Test
    void versionOverrideAppliesToEveryDiscoveredCoordinateWithoutDa() throws IOException {
        File build = widgetProject();

        int exitCode = new Cli().run(
                new String[] { "-f", build.getAbsolutePath(), "-DversionOverride=3.1.0.Final" });

        assertThat(exitCode).isEqualTo(0);
        assertThat(readFile(build)).contains("<property name=\"widget.version\" value=\"3.1.0.Final\" />");
        String report = readFile(projectDir.resolve("alignmentReport.json").toFile());
        assertThat(report).contains("\"originalGAV\" : \"org.acme:widget:1.0\"")
                .contains("\"version\" : \"3.1.0.Final\"");
    }

    @Test
    void versionOverrideEqualToTheDeclaredVersionIsUnchangedInTheReport() throws IOException {
        File build = widgetProject();
        String before = readFile(build);

        int exitCode = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-DversionOverride=1.0",
                        "-DreportJsonUnchanged=true" });

        assertThat(exitCode).isEqualTo(0);
        assertThat(readFile(build)).isEqualTo(before);
        String report = readFile(projectDir.resolve("alignmentReport.json").toFile());
        assertThat(report).contains("\"originalGAV\" : \"org.acme:widget:1.0\"")
                .contains("\"version\" : \"1.0\"");
    }

    @Test
    void alignmentVersionWithoutACoordinateChangesNothing() throws IOException {
        File build = widgetProject();
        String before = readFile(build);

        // alignment.version on its own is ignored (and warned about); versioning is not requested either.
        int exitCode = new Cli().run(
                new String[] { "-f", build.getAbsolutePath(), "-Dalignment.version=9.9" });

        assertThat(exitCode).isEqualTo(0);
        assertThat(readFile(build)).isEqualTo(before);
    }

    // ---- NCLSUP-1794: nothing to align ----

    @Test
    void versionOverrideWithZeroConcreteCoordinatesBehavesLikeSuffixWithZeroCoordinates() throws IOException {
        // A build declaring no coordinate at all, and none injected.
        Path empty = projectDir.resolve("empty");
        Files.createDirectories(empty);
        File build = write(empty.resolve("build.xml"), "<project name=\"p\"/>\n");

        daServer = stubFirstBuildDaServer();
        int overrideExit = new Cli().run(new String[] { "-f", build.getAbsolutePath(), "-DversionOverride=1.2.3" });
        int suffixExit = new Cli().run(
                new String[] {
                        "-f",
                        build.getAbsolutePath(),
                        "-DrestURL=" + daBaseUrl(),
                        "-DversionIncrementalSuffix=redhat" });

        // NCLSUP-1794: no coordinates fails eagerly (exit 10) for both options, instead of exiting 0 with
        // no alignmentReport.json, which crashes Reqour.
        assertThat(overrideExit).isEqualTo(10);
        assertThat(suffixExit).isEqualTo(10);
        assertThat(readFile(build)).isEqualTo("<project name=\"p\"/>\n");
        assertThat(empty.resolve("alignmentReport.json")).doesNotExist();
        daServer.verify(0, postRequestedFor(urlEqualTo(LOOKUP)));
    }

    // ---- helpers ----

    private File junitFixture() throws IOException, URISyntaxException {
        FixtureCopier.copyResourceDir("junit4-4.8", projectDir);
        return projectDir.resolve("build.xml").toFile();
    }

    /** A project whose pom template declares {@code org.acme:widget}, versioned through a build property. */
    private File widgetProject() throws IOException {
        Files.createDirectories(projectDir.resolve("src/maven"));
        write(
                projectDir.resolve("src/maven/widget.pom"),
                "<project>\n"
                        + "  <modelVersion>4.0.0</modelVersion>\n"
                        + "  <groupId>org.acme</groupId>\n"
                        + "  <artifactId>widget</artifactId>\n"
                        + "  <version>${widget.version}</version>\n"
                        + "</project>\n");
        return write(
                projectDir.resolve("build.xml"),
                "<project name=\"widget\" default=\"all\">\n"
                        + "  <property name=\"widget.version\" value=\"1.0\" />\n"
                        + "  <target name=\"all\" />\n"
                        + "</project>\n");
    }

    private static File write(Path path, String content) throws IOException {
        Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return path.toFile();
    }

    private WireMockServer stubFirstBuildDaServer() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(
                post(urlEqualTo(LOOKUP))
                        .willReturn(
                                aResponse().withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("[]")));
        return server;
    }

    private String daBaseUrl() {
        return "http://localhost:" + daServer.port();
    }

    private static String readFile(File file) throws IOException {
        return Files.readString(file.toPath());
    }
}
