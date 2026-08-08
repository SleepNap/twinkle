package org.gms.service.admin;

import org.gms.hotreload.RestartCoordinator;

import java.util.List;

/**
 * 管理侧访问频道的 service 契约（架构 M3-1 数据三路第②路：事务性操作经 service 接口）。
 *
 * <p>放在公共底座 core：这是**跨进程共享契约**——分进程部署时 http-api 在管理进程、
 * 频道实现在频道进程，两进程只共享公共底座（架构 4.6.6 "② 经 service 接口 RPC 到频道"）。
 * 放底座使得管理进程（不含 channel/domain-game 模块）也能编译引用本接口，M6 换 RPC 实现时
 * 接口零变化（铁律 1：不假设它在进程内）。
 *
 * <ul>
 *   <li>只暴露纯 DTO（{@link OnlinePlayer} 等），不暴露 domain-game 具体对象——http-api
 *       不依赖 domain-game（红线 4.1 / ArchUnit 规则 1）。</li>
 *   <li>单进程时 bootstrap 直接装配实现类进同一容器；分进程时装配 RPC 桩。</li>
 * </ul>
 */
public interface AdminService {

    /** 在线玩家只读快照（DTO，不含会话/角色内存对象）。 */
    record OnlinePlayer(long characterId, String name, int mapId, int level, int job) {
    }

    /** 频道在线概览。 */
    record ChannelSummary(int onlineCount, long channelId, List<OnlinePlayer> players) {
    }

    /**
     * 频道在线玩家快照（只读，经 DTO 拷贝，不泄漏内存对象）。
     */
    ChannelSummary onlineSummary();

    /**
     * 按角色 id 踢下线（管理侧运维操作，经 service 接口 RPC 到频道）。
     *
     * @return 该角色是否在线并已踢出；不在线返回 false（不报错）
     */
    boolean kick(long characterId);

    // ---- M5 admin 控制台运维操作（架构 M5-1：运维操作经 service 接口，管理侧不得直踩游戏内存） ----

    /**
     * 重载脚本（L2 热重载：ScriptManager.reload 重扫目录）。
     *
     * @return 发生变化的脚本数
     */
    int reloadScripts();

    /**
     * 请求一次主动重启（L4：DRAINING → 增量 FLUSH → 退出，红线 17）。
     *
     * <p>实现方须异步执行（不阻塞管理 API 线程）；HTTP 侧只读 {@link #restartPhase()} 跟踪进度。
     */
    void requestRestart();

    /** 当前重启编排阶段（监控面板展示用）。 */
    RestartCoordinator.Phase restartPhase();
}
