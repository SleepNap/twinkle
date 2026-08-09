package org.gms.net.netty;

import org.gms.net.packet.SessionStage;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 连接级心跳状态机（事故报告阶段 B：readerIdle → PING → PONG deadline，而非 allIdle）。
 *
 * <p>三类存活信号之一——<b>传输心跳</b>（报告 §5.2）：回答"对端是否仍能读写协议包"，
 * 只允许触发探测/关闭连接，绝不用于判定怪物控制或玩家断线。PONG 处理由
 * {@link NetworkSession} 在 HandlerRegistry 分发前拦截，是登录/选角/游戏内所有阶段
 * 共享的稳定贡献点（报告 §5.3-3）。
 *
 * <p>状态机：
 * <pre>
 *   AWAITING_READ ──readerIdle──▶ PROBING ──任意收包(含 PONG)──▶ AWAITING_READ
 *        ▲                            │
 *        │                            ▼ deadline 到且仍 PROBING
 *        └──────────────────────▶ 关闭连接（PONG 超时）
 * </pre>
 * 期限用 {@link System#nanoTime()} 单调时钟（报告 §5.3-6），避免系统时间校正误判。
 * 本类与 tick / 热重载无关：DRAINING 或 tick 暂停时心跳层保持运行（报告 §5.3-5）。
 */
final class HeartbeatGuard {

    enum State { AWAITING_READ, PROBING }

    private final long pongTimeoutNanos;
    private State state = State.AWAITING_READ;
    private long pongDeadlineNanos;
    private long lastProbeSeq;

    /** 探测序号（单调，随 PING 发往客户端，PONG 匹配用）。 */
    private final AtomicLong probeSeq = new AtomicLong();

    /** 按 SessionStage 分组的观测计数（报告 §七：心跳探测/PONG 超时按阶段分组）。 */
    private final Map<SessionStage, Long> probeCount = new EnumMap<>(SessionStage.class);
    private final Map<SessionStage, Long> pongOkCount = new EnumMap<>(SessionStage.class);
    private final Map<SessionStage, Long> pongTimeoutCount = new EnumMap<>(SessionStage.class);

    HeartbeatGuard(HeartbeatConfig config) {
        this.pongTimeoutNanos = config.pongTimeoutMillis() * 1_000_000L;
    }

    /**
     * readerIdle 触发：AWAITING_READ 时发 PING 进入 PROBING；PROBING 中若已超 deadline
     * 则关闭连接，否则继续等待（不重复发 PING）。
     *
     * @param stage   当前会话阶段（观测用）
     * @param nowNanos 单调时钟 now
     * @param sender   发包回调（构造 PING 帧）
     * @param closer   关闭连接回调
     */
    synchronized void onReaderIdle(SessionStage stage, long nowNanos,
                                   Consumer<Long> sender, Runnable closer) {
        if (state == State.AWAITING_READ) {
            long seq = probeSeq.incrementAndGet();
            lastProbeSeq = seq;
            state = State.PROBING;
            pongDeadlineNanos = nowNanos + pongTimeoutNanos;
            probeCount.merge(stage, 1L, Long::sum);
            sender.accept(seq);
        } else if (nowNanos >= pongDeadlineNanos) {
            pongTimeoutCount.merge(stage, 1L, Long::sum);
            closer.run();
        }
    }

    /**
     * 任意收包到达（PONG 或普通包）。
     *
     * <p>PROBING 中<b>任意收包视为传输响应</b>→ 解除探测（报告 §5.2：能读写协议包即传输
     * 存活；防"探测期间客户端恢复发包但 PONG 未到被误关"的假阳性）。是 PONG 额外计数。
     */
    synchronized void onInboundPacket(SessionStage stage, boolean isPong) {
        if (state == State.PROBING) {
            state = State.AWAITING_READ;
        }
        if (isPong) {
            pongOkCount.merge(stage, 1L, Long::sum);
        }
    }

    synchronized boolean isProbing() {
        return state == State.PROBING;
    }

    /** 最近一次发出的探测序号（PONG 匹配参考）。 */
    long lastProbeSeq() {
        return lastProbeSeq;
    }

    /** 观测快照（按 SessionStage 分组，报告 §七 心跳指标）。 */
    synchronized HeartbeatStats stats() {
        return new HeartbeatStats(
                Map.copyOf(probeCount), Map.copyOf(pongOkCount), Map.copyOf(pongTimeoutCount));
    }

    /** 心跳观测快照（不可变视图；缺失阶段按 0 处理）。 */
    record HeartbeatStats(Map<SessionStage, Long> probe,
                          Map<SessionStage, Long> pongOk,
                          Map<SessionStage, Long> pongTimeout) {

        static final HeartbeatStats EMPTY = new HeartbeatStats(Map.of(), Map.of(), Map.of());
    }
}
