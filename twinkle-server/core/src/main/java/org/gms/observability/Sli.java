package org.gms.observability;

/**
 * 核心 SLI 指标名（架构 12.1 的约定，M2 起统一使用）。
 *
 * <p>命名规则：{@code twinkle.<域>.<指标>}。标签统一：{@code channel}（频道）、{@code world}（大区）。
 * Micrometer 接入后自动做 Prometheus 命名转换（点号 → 下划线），此处用点号便于阅读。
 */
public final class Sli {

    /** 游戏 tick 耗时（record，标签 channel）。 */
    public static final String TICK_DURATION = "twinkle.tick.duration";
    /** tick 掉帧计数（increment，标签 channel）。 */
    public static final String TICK_DROP = "twinkle.tick.drop";
    /** 在线人数（gauge，标签 channel / world）。 */
    public static final String ONLINE = "twinkle.online";
    /** 增量 FLUSH 耗时（record）。 */
    public static final String FLUSH_DURATION = "twinkle.flush.duration";
    /** SQLite 单写队列深度（gauge）。 */
    public static final String WRITE_QUEUE_DEPTH = "twinkle.write_queue.depth";
    /** 存档延迟：下线 → 落盘（record）。 */
    public static final String SAVE_LATENCY = "twinkle.save.latency";
    /** 登录延迟（record）。 */
    public static final String LOGIN_DURATION = "twinkle.login.duration";
    /** 登录失败次数（increment）。 */
    public static final String LOGIN_FAIL = "twinkle.login.fail";

    private Sli() {
    }
}
