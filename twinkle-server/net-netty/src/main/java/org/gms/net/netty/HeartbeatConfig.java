package org.gms.net.netty;

/**
 * 心跳参数（事故报告阶段 B：分阶段 PING/PONG，而非单一 allIdle）。
 *
 * <p>{@code readerIdleMillis}：读空闲多久后发起一次 PING 探测；
 * {@code pongTimeoutMillis}：探测发出后在多久内未收到任何收包（含 PONG）即关闭连接。
 * 最坏静默关闭 ≈ readerIdle + pongTimeout。装配层读配置 {@code twinkle.net.heartbeat.*}
 * （秒）转毫秒；测试可直接配毫秒级小值。
 */
public record HeartbeatConfig(long readerIdleMillis, long pongTimeoutMillis) {

    /** 保守默认（事故报告 §4.4 建议 45-60s 量级，此处更保守）：15s 空闲探测 + 10s 超时。 */
    public static HeartbeatConfig defaults() {
        return new HeartbeatConfig(15_000, 10_000);
    }
}
