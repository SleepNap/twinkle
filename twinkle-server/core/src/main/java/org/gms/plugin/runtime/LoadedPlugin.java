package org.gms.plugin.runtime;

import org.gms.plugin.ContributionHandle;
import org.gms.plugin.Plugin;
import org.gms.plugin.PluginDescriptor;

import java.util.List;

/**
 * 已加载插件（插件运行时状态，pluginId 幂等可查）。
 *
 * @param descriptor   插件描述（manifest 解析产物）
 * @param classLoader  插件隔离 classloader（卸载时 dispose）
 * @param instance     插件主类实例（可能为 null——未声明 main-class 的纯声明式插件）
 * @param contributions 本次加载登记的贡献点句柄（unload 时统一回滚）
 */
public record LoadedPlugin(
        PluginDescriptor descriptor,
        PluginClassLoader classLoader,
        Plugin instance,
        List<ContributionHandle> contributions) {

    public String pluginId() {
        return descriptor.id();
    }
}
