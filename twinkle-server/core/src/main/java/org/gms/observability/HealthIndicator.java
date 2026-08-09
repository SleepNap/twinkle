package org.gms.observability;

/**
 * 健康检查器（架构 12.4：liveness / readiness 的组成单元）。
 *
 * <p>M0/M1 定义契约；M3 管理进程 HTTP 落地时经 Micronaut health 端点暴露。
 * 每个依赖一个 indicator：DB 连接、coordinator 连接、WZ 加载完成（readiness）；
 * 进程存活（liveness）。
 */
public interface HealthIndicator {

    /** 检查器名字，如 "liveness"、"db"、"coordinator"。 */
    String name();

    /** 当前健康状态。 */
    Status status();

    /** 健康状态。 */
    public enum Status {
        /** 正常。 */
        UP,
        /** 异常（依赖未就绪 / 超时）。 */
        DOWN
    }
}
