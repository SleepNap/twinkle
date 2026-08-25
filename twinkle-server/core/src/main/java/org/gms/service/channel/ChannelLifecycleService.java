package org.gms.service.channel;

import java.util.List;

/**
 * 频道玩家网络生命周期控制契约。
 *
 * <p>单进程实现直接控制同 JVM 的频道监听；split 实现经 coordinator RPC 控制常驻频道工作进程。
 * 停止只关闭玩家 Netty 监听，工作进程与控制链路保持在线，因此分布式频道仍可被远程重新启动。
 */
public interface ChannelLifecycleService {

    enum Topology {
        EMBEDDED,
        DISTRIBUTED
    }

    enum State {
        RUNNING,
        STOPPED,
        STARTING,
        STOPPING,
        TERMINATING,
        FAILED,
        UNAVAILABLE
    }

    record Status(
            int channelId,
            String host,
            int port,
            int onlineCount,
            State state,
            Topology topology,
            boolean controllable,
            String error) {
    }

    record CommandResult(boolean accepted, Status status) {
    }

    /** 当前已知频道；分布式下包含已注册但控制链路暂不可用的频道。 */
    List<Status> statuses();

    /** 异步启动指定频道的玩家监听。 */
    CommandResult requestStart(int channelId);

    /** 异步安全停止指定频道的玩家监听。 */
    CommandResult requestStop(int channelId);

    /**
     * 异步停止频道监听。非强制模式等待频道在途任务完成；强制模式中断在途任务后继续停止。
     */
    default CommandResult requestStop(int channelId, boolean force) {
        return requestStop(channelId);
    }

    /** 异步安全停止监听、完成增量存档并退出指定频道工作进程。 */
    CommandResult requestTerminate(int channelId);

    /**
     * 异步退出频道工作进程。强制模式仍先尝试安全存档，但存档失败或超时也会继续退出。
     */
    default CommandResult requestTerminate(int channelId, boolean force) {
        return requestTerminate(channelId);
    }
}
