package org.gms.channel.persist;

import lombok.extern.log4j.Log4j2;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.RestartCoordinator;
import org.gms.i18n.I18n;
import org.gms.tick.TickScheduler;

import java.time.Duration;

/**
 * 进程级 L4 重启编排（架构 5.4 路径 B：秒级重开 + 上下文恢复，只 FLUSH 脏数据，红线 17）。
 *
 * <p>把 {@link RestartCoordinator} 状态机接到真实组件：
 * <ul>
 *   <li><b>DRAINING</b>：tick 帧边界暂停（架构 5.1 安全点）→ 中断在途长操作（交易等）
 *       → 存档队列排空（架构 6.2 ② 单写执行器排空，主动重开不丢档的前提）。</li>
 *   <li><b>FLUSH_DIRTY</b>：只刷脏角色（红线 17，不做全量落盘）。</li>
 *   <li><b>RESTARTING</b>：{@link System#exit}（进程兜底，换 JDK/崩溃等）。</li>
 * </ul>
 */
@Log4j2
public final class RestartService {

    private static final Duration IN_FLIGHT_DRAIN_TIMEOUT = Duration.ofSeconds(30);

    private final RestartCoordinator coordinator;
    private final TickScheduler tickScheduler;
    private final EntityReloadService entityReloadService;
    private final CharacterSaveQueue saveQueue;

    public RestartService(RestartCoordinator coordinator, TickScheduler tickScheduler,
                          EntityReloadService entityReloadService, CharacterSaveQueue saveQueue) {
        this.coordinator = coordinator;
        this.tickScheduler = tickScheduler;
        this.entityReloadService = entityReloadService;
        this.saveQueue = saveQueue;
    }

    /**
     * 执行一次主动重启编排（同步，运维线程驱动）。
     *
     * @param restartProcess 关停当前进程的 runnable（如 {@code System.exit(0)}）；测试可注入 mock
     */
    public void restart(Runnable restartProcess) {
        coordinator.beginRestart(
                () -> {
                    // DRAINING：tick 帧边界暂停 → 中断在途 → 存档队列排空
                    tickScheduler.pause();
                    entityReloadService.reloadAllInFlight(id -> true); // 交易显式中断 + 回滚
                    drainSaveQueue();
                },
                saveQueue::flushAllSync,  // FLUSH_DIRTY：同步落库脏角色（红线 17，确保重启前落盘）
                restartProcess);          // RESTARTING
    }

    /**
     * 只重启频道 Netty，不退出 JVM。先停止接入和现有连接，让断链存档进入队列，再排空、
     * 增量落盘并重新监听；无论成功失败都会恢复游戏 tick，避免 Web 仍在而游戏循环永久暂停。
     */
    public void restartNetwork(Runnable stopNetwork, Runnable startNetwork) {
        try {
            coordinator.beginRestart(
                    () -> {
                        tickScheduler.pause();
                        stopNetwork.run();
                        entityReloadService.reloadAllInFlight(id -> true);
                        drainSaveQueue();
                    },
                    saveQueue::flushAllSync,
                    startNetwork);
        } finally {
            tickScheduler.resume();
        }
        if (coordinator.phase() == RestartCoordinator.Phase.FAILED) {
            throw networkFailure();
        }
        coordinator.reset();
    }

    /**
     * 安全停止频道玩家网络但保留 JVM 与控制链路。停止接入和现有连接后排空存档队列、
     * 增量落盘，再恢复游戏 tick；频道可随后由管理面重新启动监听。
     */
    public void stopNetwork(Runnable stopNetwork) {
        stopNetwork(stopNetwork, false);
    }

    /**
     * 停止频道网络。普通模式先等待全部在途操作自然完成；强制模式立即中断、回滚在途操作。
     */
    public void stopNetwork(Runnable stopNetwork, boolean force) {
        if (!force) {
            awaitInFlightOperations();
        }
        try {
            coordinator.beginRestart(
                    () -> {
                        tickScheduler.pause();
                        stopNetwork.run();
                        if (force) {
                            entityReloadService.reloadAllInFlight(id -> true);
                        } else {
                            awaitInFlightOperations();
                        }
                        drainSaveQueue();
                    },
                    saveQueue::flushAllSync,
                    () -> { });
        } finally {
            tickScheduler.resume();
        }
        if (coordinator.phase() == RestartCoordinator.Phase.FAILED) {
            throw networkFailure();
        }
        coordinator.reset();
    }

    private void awaitInFlightOperations() {
        try {
            if (!entityReloadService.awaitIdle(IN_FLIGHT_DRAIN_TIMEOUT)) {
                throw new IllegalStateException(I18n.message("error.restart.in_flight_timeout"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(I18n.message("error.restart.drain_interrupted"), e);
        }
    }

    /** 频道重新监听前的存档闸门：历史失败角色未补存成功时拒绝启动。 */
    public void ensureSavesPersisted() {
        drainSaveQueue();
        saveQueue.flushAllSync();
    }

    private void drainSaveQueue() {
        try {
            saveQueue.drain();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(I18n.message("error.restart.drain_interrupted"), e);
        }
    }

    private IllegalStateException networkFailure() {
        Throwable cause = coordinator.lastFailure();
        String message = I18n.message("error.restart.network_failed");
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            message += ": " + cause.getMessage();
        }
        return new IllegalStateException(message, cause);
    }
}
