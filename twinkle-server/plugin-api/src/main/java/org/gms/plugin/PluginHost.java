package org.gms.plugin;

import java.util.List;

/**
 * 插件宿主（架构 7.2：平台只暴露贡献点；插件系统 = 热重载系统，不重复造）。
 *
 * <p>接口在 plugin-api（SDK 纯净面），实现放装配层（bootstrap 的 TwinklePluginHost）——实现需要
 * 看见 HandlerRegistry / EventBus / TickScheduler / ScriptManager 等宿主注册表，core 看不到这些。
 *
 * <p>语义：
 * <ul>
 *   <li>{@link #applyContributions}：按 descriptor 的声明式贡献点实例化类并登记进各注册表，
 *       返回逐贡献点的回滚句柄（存进 LoadedPlugin，unload 时统一 close）。</li>
 * </ul>
 */
public interface PluginHost {

    /**
     * 应用插件声明式贡献点（manifest 驱动）。
     *
     * @param descriptor 插件描述
     * @param context    插件上下文（用 {@code classLoader()} 实例化贡献类）
     * @return 逐贡献点的回滚句柄（调用方跟踪，卸载时统一 {@link ContributionHandle#close()}
     */
    List<ContributionHandle> applyContributions(PluginDescriptor descriptor, PluginContext context);
}
