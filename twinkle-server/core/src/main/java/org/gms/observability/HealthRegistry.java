package org.gms.observability;

import java.util.Map;

/**
 * 健康检查聚合（架构 12.4）：liveness（进程活）+ readiness（依赖就绪）的注册与汇总。
 */
public interface HealthRegistry {

    /** 注册一个检查器。 */
    void register(HealthIndicator indicator);

    /** 按名字汇总各检查器状态（不可变快照）。 */
    Map<String, HealthIndicator.Status> statuses();

    /** 全部 UP？ */
    boolean isHealthy();
}
