package org.gms.service.network;

/**
 * 游戏 Netty 生命周期控制契约。
 *
 * <p>HTTP 管理层只依赖此契约，不直接依赖登录服、频道服或 Netty 实现，确保管理 HTTP
 * 在游戏网络重启期间继续可用。
 */
public interface GameNetworkService {

    enum Phase {
        RUNNING,
        RESTARTING,
        FAILED
    }

    record Status(
            Phase phase,
            boolean loginRunning,
            int loginPort,
            boolean channelRunning,
            int channelId,
            int channelPort,
            String error) {
    }

    /** 异步请求重启本进程内的游戏 Netty；已有重启任务时返回 {@code false}。 */
    boolean requestRestart();

    /** 当前游戏网络状态。 */
    Status status();
}
