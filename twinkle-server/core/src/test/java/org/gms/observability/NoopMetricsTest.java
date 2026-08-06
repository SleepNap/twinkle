package org.gms.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * 指标门面默认实现：全 no-op、不抛异常、不持有状态（M0/M1 零开销占位）。
 */
class NoopMetricsTest {

    private final Metrics metrics = new NoopMetrics();

    @Test
    @DisplayName("计数 / 标量 / 耗时均为 no-op，不抛异常")
    void allOperationsAreNoop() {
        assertThatNoException().isThrownBy(() -> metrics.increment(Sli.TICK_DROP, "channel", "1"));
        assertThatNoException().isThrownBy(() -> metrics.increment(Sli.LOGIN_FAIL, 2.0, "channel", "1"));
        assertThatNoException().isThrownBy(() -> metrics.gauge(Sli.ONLINE, 10, "channel", "1"));
        assertThatNoException().isThrownBy(() -> metrics.record(
                Sli.TICK_DURATION, Duration.ofMillis(16), "channel", "1"));
    }
}
