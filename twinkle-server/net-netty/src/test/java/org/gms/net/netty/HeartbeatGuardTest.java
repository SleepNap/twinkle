package org.gms.net.netty;

import org.gms.net.packet.SessionStage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 心跳状态机单元测试（事故报告 §七 完成标准 4 + §5.3 分阶段心跳）。
 *
 * <p>手工喂单调时钟与事件：readerIdle → 发 PING 进 PROBING；期限内 PONG → 恢复；
 * PROBING 中超时 → 关闭；PROBING 中任意收包（非 PONG）视为传输响应解除探测（防假阳性）。
 */
class HeartbeatGuardTest {

    /** 假时钟：可手动推进（nanoTime 语义，仅需单调）。 */
    private final AtomicLong clock = new AtomicLong(0);

    private final HeartbeatGuard guard =
            new HeartbeatGuard(new HeartbeatConfig(15_000, 10_000));

    private long now() {
        return clock.get();
    }

    @Test
    void readerIdleSendsPingAndEntersProbing() {
        AtomicLong lastPingSeq = new AtomicLong(-1);
        AtomicReference<Runnable> closer = new AtomicReference<>(() -> { });

        guard.onReaderIdle(SessionStage.IN_GAME, now(), seq -> lastPingSeq.set(seq), closer.get());
        assertThat(guard.isProbing()).isTrue();
        assertThat(lastPingSeq.get()).isEqualTo(1L);
    }

    @Test
    void pongWithinDeadlineRestores() {
        guard.onReaderIdle(SessionStage.IN_GAME, now(), seq -> { }, () -> { });
        clock.addAndGet(5_000_000_000L);              // 5s 后 PONG，仍在 10s 期限
        guard.onInboundPacket(SessionStage.IN_GAME, true);
        assertThat(guard.isProbing()).isFalse();
        // 未关闭，且 pongOk 计数 +1
        HeartbeatGuard.HeartbeatStats stats = guard.stats();
        assertThat(stats.pongOk().get(SessionStage.IN_GAME)).isEqualTo(1);
        assertThat(stats.probe().get(SessionStage.IN_GAME)).isEqualTo(1);
    }

    @Test
    void timeoutClosesConnection() {
        AtomicBoolean closed = new AtomicBoolean(false);
        guard.onReaderIdle(SessionStage.IN_GAME, now(), seq -> { }, () -> closed.set(true));
        clock.addAndGet(11_000_000_000L);             // 11s > 10s 期限
        guard.onReaderIdle(SessionStage.IN_GAME, now(), seq -> { }, () -> closed.set(true));
        assertThat(closed).isTrue();
        assertThat(guard.stats().pongTimeout().get(SessionStage.IN_GAME)).isEqualTo(1);
    }

    @Test
    void probingAnyInboundPacketClearsProbe() {
        // 探测期间客户端恢复发包（非 PONG）：应视为传输响应，不能误关（报告 §5.2）
        guard.onReaderIdle(SessionStage.LOGIN, now(), seq -> { }, () -> { });
        clock.addAndGet(7_000_000_000L);
        guard.onInboundPacket(SessionStage.LOGIN, false);
        assertThat(guard.isProbing()).isFalse();
        // 后续 readerIdle 从 AWAITING_READ 重新探测（不会因旧 PROBING 立即超时关闭）
        clock.addAndGet(11_000_000_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        guard.onReaderIdle(SessionStage.LOGIN, now(), seq -> { }, () -> closed.set(true));
        assertThat(closed).isFalse();                 // 重新探测而非关闭
        assertThat(guard.isProbing()).isTrue();
    }

    @Test
    void statsGroupedByStage() {
        guard.onReaderIdle(SessionStage.LOGIN, now(), seq -> { }, () -> { });
        guard.onInboundPacket(SessionStage.LOGIN, true);
        guard.onReaderIdle(SessionStage.IN_GAME, now(), seq -> { }, () -> { });
        clock.addAndGet(11_000_000_000L);
        guard.onReaderIdle(SessionStage.IN_GAME, now(), seq -> { }, () -> { });

        HeartbeatGuard.HeartbeatStats stats = guard.stats();
        assertThat(stats.probe().get(SessionStage.LOGIN)).isEqualTo(1);
        assertThat(stats.pongOk().get(SessionStage.LOGIN)).isEqualTo(1);
        assertThat(stats.pongTimeout().get(SessionStage.IN_GAME)).isEqualTo(1);
    }
}
