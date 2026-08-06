package org.gms.observability;

import java.time.Duration;

/**
 * 指标门面（架构 12.1：Micrometer 进进程内注册表，M3 挂 HTTP 管理端点）。
 *
 * <p>M0/M1 定稿接口，M2 游戏逻辑按此埋点（tick 时长/掉帧、在线人数、存档延迟等，见 {@link Sli}）。
 * 默认实现 {@link NoopMetrics} 零开销；M3 接入 Micrometer 时替换为 Micrometer 实现，埋点代码不动。
 * 三个原语：
 * <ul>
 *   <li>计数（tick 掉帧、登录失败）</li>
 *   <li>标量瞬时值（在线人数、写队列深度）</li>
 *   <li>耗时（tick 时长、存档延迟）</li>
 * </ul>
 *
 * <p>标签以 name=value 交替对传入（如 {@code "channel", "1"}）。
 */
public interface Metrics {

    /** 计数 +1。 */
    void increment(String name, String... tags);

    /** 计数按增量累加。 */
    void increment(String name, double delta, String... tags);

    /** 标量瞬时值。 */
    void gauge(String name, double value, String... tags);

    /** 记录一次耗时。 */
    void record(String name, Duration duration, String... tags);
}
