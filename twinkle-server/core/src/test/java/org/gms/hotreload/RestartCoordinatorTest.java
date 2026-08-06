package org.gms.hotreload;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RestartCoordinator 状态机测试（架构 5.4 L4）。
 *
 * <p>验证：正常编排走 DRAINING → FLUSH_DIRTY → RESTARTING；失败编排 → FAILED（进程保持运行）；
 * 状态监听通知顺序正确。
 */
class RestartCoordinatorTest {

    @Test
    void happyPath_drainsThenFlushesThenRestarts() {
        RestartCoordinator coordinator = new RestartCoordinator();
        List<RestartCoordinator.Phase> observed = new ArrayList<>();
        coordinator.onPhaseChange(observed::add);

        AtomicInteger drained = new AtomicInteger();
        AtomicInteger flushed = new AtomicInteger();
        AtomicInteger restarted = new AtomicInteger();

        coordinator.beginRestart(
                drained::incrementAndGet,
                flushed::incrementAndGet,
                restarted::incrementAndGet);

        assertThat(drained.get()).isEqualTo(1);
        assertThat(flushed.get()).isEqualTo(1);
        assertThat(restarted.get()).isEqualTo(1);
        assertThat(observed).containsExactly(
                RestartCoordinator.Phase.DRAINING,
                RestartCoordinator.Phase.FLUSH_DIRTY,
                RestartCoordinator.Phase.RESTARTING,
                RestartCoordinator.Phase.RESTORED);
    }

    @Test
    void drainFailure_fallsToFailedWithoutFlushing() {
        RestartCoordinator coordinator = new RestartCoordinator();
        AtomicInteger flushed = new AtomicInteger();

        coordinator.beginRestart(
                () -> { throw new IllegalStateException("排空失败"); },
                flushed::incrementAndGet,
                () -> {});

        assertThat(coordinator.phase()).isEqualTo(RestartCoordinator.Phase.FAILED);
        assertThat(flushed.get()).isZero();
        assertThat(coordinator.lastFailure()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void flushFailure_fallsToFailed() {
        RestartCoordinator coordinator = new RestartCoordinator();
        AtomicInteger restarted = new AtomicInteger();

        coordinator.beginRestart(
                () -> {},
                () -> { throw new RuntimeException("flush 失败"); },
                restarted::incrementAndGet);

        assertThat(coordinator.phase()).isEqualTo(RestartCoordinator.Phase.FAILED);
        assertThat(restarted.get()).isZero();
    }

    @Test
    void resetReturnsToRunning() {
        RestartCoordinator coordinator = new RestartCoordinator();
        coordinator.beginRestart(
                () -> { throw new RuntimeException("boom"); },
                () -> {}, () -> {});
        assertThat(coordinator.phase()).isEqualTo(RestartCoordinator.Phase.FAILED);

        coordinator.reset();
        assertThat(coordinator.phase()).isEqualTo(RestartCoordinator.Phase.RUNNING);
        assertThat(coordinator.lastFailure()).isNull();
    }

    @Test
    void listenerExceptionDoesNotBreakStateMachine() {
        RestartCoordinator coordinator = new RestartCoordinator();
        AtomicReference<Throwable> listenerErr = new AtomicReference<>();
        coordinator.onPhaseChange(p -> { throw new RuntimeException("listener boom"); });
        // 不应抛异常，状态照常推进
        coordinator.beginRestart(() -> {}, () -> {}, () -> {});
        assertThat(coordinator.phase()).isEqualTo(RestartCoordinator.Phase.RESTORED);
    }

    @Test
    void initialStateIsRunning() {
        RestartCoordinator coordinator = new RestartCoordinator();
        assertThat(coordinator.phase()).isEqualTo(RestartCoordinator.Phase.RUNNING);
    }
}
