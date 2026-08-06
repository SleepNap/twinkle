package org.gms.observability;

import java.time.Duration;

/**
 * 指标门面默认实现：全 no-op，零内存零 CPU（2C2G 红线 9.2）。
 *
 * <p>M0/M1 阶段无 SLI 可埋（游戏逻辑 M2 才有），Noop 让 M2 代码第一天就能写
 * {@code metrics.increment(Sli.TICK_DROP)}，无需等 Micrometer 接入。
 */
public final class NoopMetrics implements Metrics {

    @Override
    public void increment(String name, String... tags) {
        // no-op
    }

    @Override
    public void increment(String name, double delta, String... tags) {
        // no-op
    }

    @Override
    public void gauge(String name, double value, String... tags) {
        // no-op
    }

    @Override
    public void record(String name, Duration duration, String... tags) {
        // no-op
    }
}
