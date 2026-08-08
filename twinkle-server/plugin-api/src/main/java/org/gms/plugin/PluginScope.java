package org.gms.plugin;

/**
 * 插件部署作用域（架构 4.6.1 / 7.2：按数据属主分发）。
 *
 * <ul>
 *   <li><b>CHANNEL</b> 频道插件：战斗/任务等游戏逻辑，就近进频道进程。</li>
 *   <li><b>REALM</b> 大区插件：公会/事件等共享状态，按数据属主——真值在 coordinator 就进管理进程。</li>
 *   <li><b>PLATFORM</b> 平台插件：AI / API 工具，服务所有人，进管理进程。</li>
 * </ul>
 *
 * <p>M4 单进程装配下三类插件同进程加载；作用域决定部署分发（M6 分进程时按此分发），
 * 不改变贡献点注册语义。
 */
public enum PluginScope {

    CHANNEL,
    REALM,
    PLATFORM
}
