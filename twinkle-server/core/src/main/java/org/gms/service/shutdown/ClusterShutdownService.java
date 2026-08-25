package org.gms.service.shutdown;

import java.util.List;

/** 管理进程发起的整个服务集群安全关闭契约。 */
public interface ClusterShutdownService {

    enum Phase {
        RUNNING,
        DRAINING_CHANNELS,
        TERMINATING_CHANNELS,
        STOPPING_COORDINATOR,
        PARTIAL_FAILURE
    }

    record Status(
            Phase phase,
            int targetCount,
            int completedCount,
            List<Integer> failedChannelIds,
            String error) {
    }

    record CommandResult(boolean accepted, Status status) {
    }

    /** 异步发起集群关闭；响应返回后才开始退出管理进程，保证 HTTP 调用方收到确认。 */
    CommandResult requestShutdown();

    /** 强制模式下仍先安全排空，但失败或超时不会阻止后续进程退出。 */
    default CommandResult requestShutdown(boolean force) {
        return requestShutdown();
    }

    Status status();
}
