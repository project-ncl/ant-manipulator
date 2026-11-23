package org.jboss.pnc.antmanipulator.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pins the documented fail-fast behaviour of {@link Cli#toRef}: a coordinate Atlas rejects aborts the run
 * (PME parity) rather than being skipped.
 */
class CliToRefTest {

    @Test
    void rejectedVersionThrowsNamingTheCoordinate() {
        assertThatThrownBy(() -> Cli.toRef("groupId", "artifactId", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId:artifactId:");
    }

    @Test
    void validVersionIsConverted() {
        assertThat(Cli.toRef("org.example", "lib", "1.0.0").getVersionString()).isEqualTo("1.0.0");
    }
}
