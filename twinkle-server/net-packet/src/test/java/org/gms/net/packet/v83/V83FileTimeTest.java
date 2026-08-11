package org.gms.net.packet.v83;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Windows FILETIME 的确定性编码测试。 */
public class V83FileTimeTest {

    @Test
    public void unixEpochUsesFixedUtcOffset() {
        assertThat(V83FileTime.encode(0)).isEqualTo(116444736000000000L);
    }

    @Test
    public void millisecondsAdvanceByTenThousandTicks() {
        assertThat(V83FileTime.encode(1) - V83FileTime.encode(0)).isEqualTo(10_000L);
    }

    @Test
    public void protocolSentinelsRemainStable() {
        assertThat(V83FileTime.encode(-1)).isEqualTo(150842304000000000L);
        assertThat(V83FileTime.encode(-2)).isEqualTo(94354848000000000L);
        assertThat(V83FileTime.encode(-3)).isEqualTo(150841440000000000L);
    }
}
