package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.gms.observability.HealthRegistry;
import org.gms.observability.MemoryHealthRegistry;
import org.gms.observability.Metrics;
import org.gms.observability.NoopMetrics;

/**
 * 可观测装配（架构 12：Metrics + HealthRegistry 挂 bean）。
 *
 * <p>M2 已建 observability 包（Metrics/Health/MdcKeys/Sli）但无装配 bean；M3-1 HTTP
 * 落地时挂接：管理侧 HTTP 读健康检查、限流计数器埋点。
 *
 * <p>默认实现零开销（NoopMetrics / 内存健康注册表），2C2G 红线不引入常驻重服务；
 * M3-2 之后接入 Micrometer 时替换 Metrics 实现，埋点代码不动。
 */
@Factory
public class ObservabilityConfig {

    @Bean
    @Singleton
    public Metrics metrics() {
        return new NoopMetrics();
    }

    @Bean
    @Singleton
    public HealthRegistry healthRegistry() {
        return new MemoryHealthRegistry();
    }
}
