package org.gms.plugin;

import java.util.function.Consumer;

/**
 * 命令式贡献点注册门面（插件在 {@link Plugin#start} 中经 {@link PluginContext#contributions()} 使用）。
 *
 * <p>声明式贡献点（manifest 驱动）由 {@link PluginHost#applyContributions} 处理，插件代码通常无需
 * 直接调用本门面；本门面服务"需要运行时决定贡献什么"的场景（如按配置注册不同 opcode）。
 *
 * <p>每类贡献点版本化（红线 13）：{@code version} 参与宿主注册表的单调性判定（替换时须递增）。
 */
public interface ContributionRegistrar {

    /**
     * 注册一个贡献点对象（类型由 {@code contributionType} 标识，宿主按类型落进对应注册表）。
     *
     * @param contributionType 贡献点类型（PACKET_HANDLER / TICK_HANDLER / LOGIC_SYSTEM 等）
     * @param contribution     贡献点实例（宿主按类型 cast）
     * @param version          贡献点版本（替换时须高于现版本）
     * @return 注册句柄（卸载时 close）
     */
    <T> ContributionHandle register(String contributionType, T contribution, int version);

    /**
     * 订阅事件总线目标（事件监听贡献点的命令式入口）。
     *
     * @return 退订句柄（卸载时 close）
     */
    <T> ContributionHandle subscribe(String target, Class<T> eventType, Consumer<T> consumer);
}
