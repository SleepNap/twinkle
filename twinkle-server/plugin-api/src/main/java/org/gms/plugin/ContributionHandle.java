package org.gms.plugin;

/**
 * 单个贡献点的注册句柄（插件卸载 / 热重载时统一回滚）。
 *
 * <p>宿主在插件加载期逐贡献点登记并持有 handle；{@link #close()} 从对应注册表移除
 * （HandlerRegistry.unregister / EventBus 退订 / TickScheduler.unregister 等）。必须幂等。
 */
public interface ContributionHandle extends AutoCloseable {

    /** 移除该贡献点（幂等：重复调用无副作用）。 */
    @Override
    void close();
}
