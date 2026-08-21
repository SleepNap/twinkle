package org.gms.plugin;

import java.util.List;

/**
 * 插件描述（manifest {@code META-INF/twinkle-plugin.properties} 解析产物，架构 7.3 声明式注册）。
 *
 * <p>字段对齐 VSCode 的 {@code contributes} 模型：平台只暴露贡献点，插件声明"我贡献什么"。
 * 每类贡献点携带自身版本（红线 13：可装卸即兼容面；替换时版本须单调递增）。
 *
 * @param id          插件唯一 id（如 {@code com.acme.boss}）
 * @param name        展示名
 * @param version     插件版本（字符串，如 {@code 1.2.0}）
 * @param scope       部署作用域（channel / realm / platform）
 * @param sdkVersion  声明的 SDK 版本（加载时校验 ∈ [SdkVersion.MIN_COMPATIBLE, CURRENT]）
 * @param mainClass   插件主类（可选，实现 {@link Plugin}）
 * @param packetHandlers      包处理器贡献点
 * @param tickHandlers        定时任务（tick handler）贡献点
 * @param eventListeners      事件监听贡献点
 * @param scriptNamespaces    脚本命名空间贡献点
 * @param logicSystems        游戏逻辑系统贡献点
 * @param httpEndpoints        HTTP 路由贡献点（M4 声明，接线留 M5）
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        PluginScope scope,
        int sdkVersion,
        String mainClass,
        List<PacketHandlerContribution> packetHandlers,
        List<TickHandlerContribution> tickHandlers,
        List<EventListenerContribution> eventListeners,
        List<ScriptNamespaceContribution> scriptNamespaces,
        List<LogicSystemContribution> logicSystems,
        List<HttpEndpointContribution> httpEndpoints) {

    /** 包处理器贡献点：绑定收包 opcode（RecvOpcode 枚举名），版本化。 */
    public record PacketHandlerContribution(String opcode, String className, int version) {
    }

    /** tick 任务贡献点：实现 {@code org.gms.tick.TickHandler}。 */
    public record TickHandlerContribution(String className, int version) {
    }

    /** 事件监听贡献点：订阅 EventBus 目标上某类型事件。 */
    public record EventListenerContribution(String target, String eventClassName, String className, int version) {
    }

    /** 脚本命名空间贡献点：从插件 jar {@code scripts/} 挂载脚本目录。 */
    public record ScriptNamespaceContribution(String namespace) {
    }

    /** 游戏逻辑系统贡献点：注册进 LogicSystemRegistry，可替换内置系统。 */
    public record LogicSystemContribution(String key, String className, int version) {
    }

    /** HTTP 路由贡献点（方法 + 路径，M4 声明不接线）。 */
    public record HttpEndpointContribution(String method, String path, String className, int version) {
    }
}
