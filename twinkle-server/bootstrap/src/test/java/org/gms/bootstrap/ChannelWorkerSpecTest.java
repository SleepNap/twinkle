package org.gms.bootstrap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelWorkerSpecTest {

    @Test
    void parsesMultipleChannelsAndDerivesStableWorkerId() {
        ChannelWorkerSpec spec = new ChannelWorkerSpec("", "21:10000,1:8584,8:9000",
                9, "10.0.0.8", 8592);

        assertThat(spec.workerId()).isEqualTo("worker-1");
        assertThat(spec.endpoints()).extracting(ChannelWorkerSpec.Endpoint::channelId)
                .containsExactly(1, 8, 21);
        assertThat(spec.endpoint(8).port()).isEqualTo(9000);
        assertThat(spec.endpoint(8).host()).isEqualTo("10.0.0.8");
    }

    @Test
    void emptyWorkerListKeepsLegacySingleChannelConfiguration() {
        ChannelWorkerSpec spec = new ChannelWorkerSpec("legacy", "", 7, "127.0.0.1", 8590);

        assertThat(spec.workerId()).isEqualTo("legacy");
        assertThat(spec.endpoints()).containsExactly(
                new ChannelWorkerSpec.Endpoint(7, "127.0.0.1", 8590));
    }

    @Test
    void duplicatePortIsRejectedBeforeAnyServerStarts() {
        assertThatThrownBy(() -> new ChannelWorkerSpec("worker-a", "1:8584,2:8584",
                1, "127.0.0.1", 8584))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate channel port");
    }
}
