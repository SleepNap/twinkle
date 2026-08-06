package org.gms.tick;

/**
 * tick 执行单元（可替换层逻辑系统实现，经此接入游戏循环）。
 *
 * <p>约束（红线 12）：实现必须<b>逻辑无状态</b>——从接口读状态 → 计算 → 写回，
 * 不持有跨操作状态；否则热重载安全点（tick 帧边界暂停，架构 5.1）失效。
 *
 * <p>约束：handler 不得抛异常上抛（异常由 {@link GameTickLoop} 记录并继续下一 tick，
 * 单个 handler 出错不拖垮整服循环）。
 */
@FunctionalInterface
public interface TickHandler {

    /**
     * 每 tick 调用一次。
     *
     * @param tickCount 当前 tick 序号（从 1 起，单调递增）
     */
    void tick(long tickCount);
}
