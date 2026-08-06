package org.gms.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 健康检查聚合默认实现：默认 liveness 恒 UP；注册的 readiness 检查器反映其状态；
 * isHealthy 需全部 UP。
 */
class MemoryHealthRegistryTest {

    @Test
    @DisplayName("默认 liveness 恒 UP")
    void defaultLivenessIsUp() {
        HealthRegistry registry = new MemoryHealthRegistry();

        assertThat(registry.statuses())
                .containsEntry("liveness", HealthIndicator.Status.UP);
        assertThat(registry.isHealthy()).isTrue();
    }

    @Test
    @DisplayName("注册的 readiness 检查器反映状态，任一 DOWN 则整体不健康")
    void registeredIndicatorsDriveOverallHealth() {
        MemoryHealthRegistry registry = new MemoryHealthRegistry();
        registry.register(upIndicator("db"));
        registry.register(upIndicator("wz"));

        assertThat(registry.isHealthy()).isTrue();
        assertThat(registry.statuses())
                .containsEntry("db", HealthIndicator.Status.UP)
                .containsEntry("wz", HealthIndicator.Status.UP);

        registry.register(downIndicator("coordinator"));
        assertThat(registry.isHealthy()).isFalse();
        assertThat(registry.statuses().get("coordinator")).isEqualTo(HealthIndicator.Status.DOWN);
    }

    @Test
    @DisplayName("同名 indicator 覆盖注册")
    void sameNameOverwrites() {
        MemoryHealthRegistry registry = new MemoryHealthRegistry();
        registry.register(upIndicator("db"));
        registry.register(downIndicator("db"));
        assertThat(registry.statuses().get("db")).isEqualTo(HealthIndicator.Status.DOWN);
    }

    private static HealthIndicator upIndicator(String name) {
        return new HealthIndicator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Status status() {
                return Status.UP;
            }
        };
    }

    private static HealthIndicator downIndicator(String name) {
        return new HealthIndicator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Status status() {
                return Status.DOWN;
            }
        };
    }
}
