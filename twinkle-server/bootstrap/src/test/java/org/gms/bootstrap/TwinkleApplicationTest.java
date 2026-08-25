package org.gms.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TwinkleApplicationTest {

    @Test
    void startupElapsedTimeUsesSecondsWithThreeDecimalPlaces() {
        assertThat(TwinkleApplication.startupElapsedSeconds()).matches("\\d+\\.\\d{3}");
    }
}
