package org.gms.tick;

/**
 * 游戏循环调度接口（架构 core"调度"、5.1：游戏 tick 单线程，换点干净）。
 *
 * <p>接口先行（铁律 1）：进程内用 {@link GameTickLoop} 单线程实现；分布式/多进程
 * 若需远程调度，换实现、接口不变。可替换层逻辑系统实现 {@link TickHandler} 注册进来。
 *
 * <p>热重载安全点：{@link #pause()} 在 tick 帧边界暂停（当前 tick 完成后不再启动下一个），
 * L3 换代时在此卸载旧 handler、注册新 handler，再 {@link #resume()}——无并发执行中的逻辑。
 */
public interface TickScheduler {

    /** 基础调度周期（毫秒）。 */
    public long intervalMillis();

    /** 把业务周期换算成 tick 数；不能整除时向上取整，避免任务提前执行。 */
    public default long ticksFor(long periodMillis) {
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("periodMillis must be positive");
        }
        return Math.max(1L, Math.ceilDiv(periodMillis, intervalMillis()));
    }

    /** 注册 tick handler（换代时可先 unregister 旧再 register 新）。 */
    public void register(TickHandler handler);

    public void unregister(TickHandler handler);

    public int handlerCount();

    /** 启动循环线程。 */
    public void start();

    /** 停止循环线程。 */
    public void stop();

    /** 暂停在安全点（热重载/停服用）。 */
    public void pause();

    /** 恢复 tick。 */
    public void resume();

    public boolean isPaused();

    /** 已执行的 tick 数（从 1 起）。 */
    public long tickCount();
}
