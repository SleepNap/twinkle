package org.gms.observability;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康检查默认实现：进程内内存聚合，线程安全。
 *
 * <p>构造时注册默认 {@code liveness} 检查器（进程活着即 UP）。readiness 类检查器
 * （db / coordinator / wz）由各依赖就绪后在装配期 {@link #register}。
 */
public final class MemoryHealthRegistry implements HealthRegistry {

    private final Map<String, HealthIndicator> indicators = new ConcurrentHashMap<>();

    public MemoryHealthRegistry() {
        register(new HealthIndicator() {
            @Override
            public String name() {
                return "liveness";
            }

            @Override
            public Status status() {
                return Status.UP;
            }
        });
    }

    @Override
    public void register(HealthIndicator indicator) {
        indicators.put(indicator.name(), indicator);
    }

    @Override
    public Map<String, HealthIndicator.Status> statuses() {
        Map<String, HealthIndicator.Status> snapshot = new ConcurrentHashMap<>();
        indicators.forEach((name, indicator) -> snapshot.put(name, indicator.status()));
        return Collections.unmodifiableMap(snapshot);
    }

    @Override
    public boolean isHealthy() {
        return indicators.values().stream()
                .allMatch(indicator -> indicator.status() == HealthIndicator.Status.UP);
    }
}
