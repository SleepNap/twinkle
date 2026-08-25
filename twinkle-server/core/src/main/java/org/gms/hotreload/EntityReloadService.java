package org.gms.hotreload;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.hotreload.versioned.VersionGate;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/**
 * 按实体渐进重载服务（架构 5.3：L3 热重载的编排门面）。
 *
 * <p>一次重载 = 对每个目标实体：等其自然结束（已在安全点则立即）或显式中断 + 回滚，
 * 再换代版本门。**绝不做全服同步原子重载**——本服务按实体逐个推进，每个实体只在
 * 无在途操作的安全点切换。
 *
 * <p>三阶段（对每个实体）：
 * <ol>
 *   <li>safeOnly：筛出已在安全点的实体（丢物等单操作实体天然安全）。</li>
 *   <li>interrupt：对仍在途的实体，经业务方提供的 {@code interrupt} 回调显式中断
 *       （如交易取消 + 回滚，玩家看到"交易被取消"）。</li>
 *   <li>advanceVersion：全部实体就绪后换代版本门，旧逻辑迟到写被拒。</li>
 * </ol>
 *
 * <p>线程模型：由调用方（管理 API / 运维线程）驱动，同步编排。游戏 tick 单线程不受影响。
 */
@Log4j2
public final class EntityReloadService {



    /** 单次重载结果。 */
    public record ReloadResult(int safeSwitched, int interrupted, long newVersion) {
    }

    private final EntityReloadCoordinator coordinator;
    private final VersionGate versionGate;

    public EntityReloadService(EntityReloadCoordinator coordinator, VersionGate versionGate) {
        this.coordinator = coordinator;
        this.versionGate = versionGate;
    }

    /**
     * 执行一次按实体渐进重载。
     *
     * @param targetEntities 本次要重载的实体 id
     * @param interrupt      对仍在途实体的显式中断回调（业务方实现：交易取消+回滚等）。
     *                       回调返回 true 表示已成功中断（实体回到安全点）。
     * @return 安全切换数 / 中断数 / 新版本号
     */
    public ReloadResult reload(List<Long> targetEntities, LongPredicate interrupt) {
        int safeSwitched = 0;
        int interrupted = 0;

        // 阶段 1+2：逐实体——安全点直接切，在途则显式中断
        for (Long entityId : targetEntities) {
            if (coordinator.isSafe(entityId)) {
                safeSwitched++;
                continue;
            }
            // 在途操作：显式中断（交易取消+回滚）。中断后实体回安全点。
            boolean ok = interrupt != null && interrupt.test(entityId);
            if (ok) {
                interrupted++;
            } else {
                log.warn(I18n.message("log.reload.entity_skip"), entityId);
            }
        }

        // 阶段 3：换代版本门——旧逻辑迟到写从此被拒
        long newVersion = coordinator.advanceVersion(versionGate);
        log.info(I18n.message("log.reload.completed"),
                safeSwitched, interrupted, newVersion);
        return new ReloadResult(safeSwitched, interrupted, newVersion);
    }

    /** 便捷：从当前全部在途实体里中断指定的那批。 */
    public ReloadResult reloadAllInFlight(LongPredicate interrupt) {
        List<Long> inFlight = new ArrayList<>(coordinator.inFlightEntities());
        return reload(inFlight, interrupt);
    }

    /** 等待当前全部在途实体操作自然完成。 */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        return coordinator.awaitIdle(timeout);
    }
}
