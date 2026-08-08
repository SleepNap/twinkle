package org.gms.channel.persist;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.RestartCoordinator;
import org.gms.tick.TickScheduler;

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
public final class RestartService {

    private static final Logger LOG = LogManager.getLogger(RestartService.class);

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
                    try {
                        saveQueue.drain();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("DRAINING 存档队列被中断", e);
                    }
                },
                saveQueue::flushAllSync,  // FLUSH_DIRTY：同步落库脏角色（红线 17，确保重启前落盘）
                restartProcess);          // RESTARTING
    }
}
