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

    /** 注册 tick handler（换代时可先 unregister 旧再 register 新）。 */
    void register(TickHandler handler);

    void unregister(TickHandler handler);

    int handlerCount();

    /** 启动循环线程。 */
    void start();

    /** 停止循环线程。 */
    void stop();

    /** 暂停在安全点（热重载/停服用）。 */
    void pause();

    /** 恢复 tick。 */
    void resume();

    boolean isPaused();

    /** 已执行的 tick 数（从 1 起）。 */
    long tickCount();
}
