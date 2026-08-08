package org.gms.plugin;

/**
 * 插件生命周期（架构 7.1：VSCode 模型 = 贡献点 + SDK 版本化 + 隔离）。
 *
 * <p>插件主类（manifest {@code plugin.main-class}，可选）实现本接口：
 * <ul>
 *   <li>{@link #start}：命令式贡献点注册入口——经 {@link PluginContext#contributions()} 注册
 *       PacketHandler / 事件监听 / tick 任务等。声明式贡献点（manifest 驱动）由宿主
 *       {@link PluginHost#applyContributions} 在 start 之前完成。</li>
 *   <li>{@link #stop}：清理插件自有资源（宿主负责回滚已注册贡献点，插件只需释放自身持有的资源）。</li>
 * </ul>
 *
 * <p>可替换层纪律（红线 12）：插件逻辑不得持有跨操作状态；重载 = 新 loader + 新实例 + 重新绑定。
 */
public interface Plugin {

    /**
     * 插件启动（命令式贡献点注册入口）。
     *
     * @param context 插件上下文（服务访问 / 贡献点注册门面）
     */
    void start(PluginContext context) throws Exception;

    /**
     * 插件停止（释放插件自有资源；贡献点回滚由宿主统一负责）。
     */
    default void stop(PluginContext context) throws Exception {
    }
}
