package org.gms.hotreload;

import org.gms.hotreload.versioned.VersionGate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按实体渐进重载协调器（架构 5.3：重载原子单元 = 单个玩家/频道，绝不做全服同步原子重载）。
 *
 * <p>职责：跟踪每个实体是否有**在途操作**，让重载只在"无在途操作的安全点"逐实体切换：
 * <ul>
 *   <li>丢物是单操作（单 tick 内删 item）天然安全——实体不进入长操作跟踪即默认安全。</li>
 *   <li>交易是长操作（跨 tick）——实体经 {@link #beginOperation} 标记"在途"，期间不可重载；
 *       重载时要么等其自然结束（{@link #endOperation}，秒级），要么显式中断 + 回滚
 *       （{@link #reloadEntity} 配合业务方 interrupt 回调）。</li>
 * </ul>
 *
 * <p>可感知极限是"交易被中断"，不是"东西没了/多出来了"。配合 {@link VersionGate}：
 * 换代后旧逻辑的迟到写被版本门挡下（{@code VersionDecision#STALE}），本协调器只负责
 * "哪个实体此刻能切"。两者是防复制 bug 的两个面。
 *
 * <p>线程模型：游戏 tick 单线程，但断链/管理 API 可并发到达，用 ConcurrentMap 兜底。
 */
public final class EntityReloadCoordinator {

    /** 在途操作计数：实体 id → 在途操作数（&gt;0 表示不可重载）。 */
    private final ConcurrentMap<Long, Integer> inFlightOperations = new ConcurrentHashMap<>();

    /**
     * 实体进入长操作（如交易）。重复进入计数 +1。
     *
     * @return true=首次进入（此前无在途）；false=嵌套进入
     */
    public boolean beginOperation(long entityId) {
        return inFlightOperations.merge(entityId, 1, Integer::sum) == 1;
    }

    /**
     * 实体结束一次长操作（交易结束/中断）。计数 -1，归零移除。
     *
     * @return true=已回到安全点（无在途）；false=仍嵌套在途
     */
    public boolean endOperation(long entityId) {
        Integer result = inFlightOperations.computeIfPresent(entityId, (k, v) -> v <= 1 ? null : v - 1);
        return result == null;
    }

    /** 实体是否在安全点（无在途操作）。未跟踪的实体天然安全。 */
    public boolean isSafe(long entityId) {
        return !inFlightOperations.containsKey(entityId);
    }

    /** 实体当前是否在途（长操作进行中）。 */
    public boolean inOperation(long entityId) {
        return !isSafe(entityId);
    }

    /** 从一组实体中筛出当前在安全点的（逐实体切换用）。 */
    public List<Long> safeOnly(Iterable<Long> entityIds) {
        java.util.ArrayList<Long> safe = new java.util.ArrayList<>();
        for (Long id : entityIds) {
            if (isSafe(id)) {
                safe.add(id);
            }
        }
        return safe;
    }

    /** 当前跟踪的在途实体（快照）。 */
    public Set<Long> inFlightEntities() {
        return Set.copyOf(inFlightOperations.keySet());
    }

    /** 当前在途实体数（观测）。 */
    public int inFlightCount() {
        return inFlightOperations.size();
    }

    /**
     * 等待所有实体回到安全点（无在途操作）。L4 DRAINING 阶段使用：排空在途操作后再增量 FLUSH。
     *
     * <p>轮询实现（2C2G 红线：不引入条件变量 / 显式通知机制）。超时返回 false，调用方决定
     * 是否显式中断（{@code EntityReloadService#reloadAllInFlight}）。
     *
     * @param timeout 最长等待
     * @return true=超时前全部回到安全点；false=超时仍有在途
     */
    public boolean awaitIdle(java.time.Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlightCount() > 0) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    /**
     * 版本门换代（L3 热重载核心步骤）：版本 +1，旧逻辑迟到写从此被拒。
     *
     * @return 新版本号
     */
    public long advanceVersion(VersionGate gate) {
        return gate.onReload();
    }
}
