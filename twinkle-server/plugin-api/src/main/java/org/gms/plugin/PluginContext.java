package org.gms.plugin;

import java.net.URL;

/**
 * 插件上下文（插件运行时经此访问宿主服务与资源，信任边界 = 全权但经接口，架构 7.2）。
 *
 * <p>可替换层纪律（红线 11/12）：插件经 {@link #getService} 访问宿主服务（application service /
 * 注册表），不直接持有游戏对象具体类；插件逻辑不得持有跨操作状态（重载 = 新实例）。
 */
public interface PluginContext {

    /** 插件唯一 id（descriptor.id）。 */
    String pluginId();

    /** 插件声明的 SDK 版本。 */
    int sdkVersion();

    /** 插件完整描述。 */
    PluginDescriptor descriptor();

    /** 插件自身的 classloader（宿主实例化贡献类、插件加载自有资源用）。 */
    ClassLoader classLoader();

    /** 命令式贡献点注册门面。 */
    ContributionRegistrar contributions();

    /**
     * 访问宿主服务（application service / 注册表等）。
     *
     * @param serviceType 服务接口类型
     * @return 宿主实现；不可用抛 {@link IllegalArgumentException}
     */
    <T> T getService(Class<T> serviceType);

    /** 插件 jar 内资源（脚本、配置等）。 */
    URL resource(String path);

    /**
     * 登记一个需随插件生命周期统一清理的句柄（如自行开启的线程 / 连接）。
     *
     * @return 原句柄（便于链式调用）
     */
    AutoCloseable track(AutoCloseable handle);
}
