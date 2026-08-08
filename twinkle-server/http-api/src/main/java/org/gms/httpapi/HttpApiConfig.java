package org.gms.httpapi;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.event.EventBus;
import org.gms.httpapi.limit.ApiRateLimiter;
import org.gms.httpapi.mirror.OnlinePlayerMirror;
import org.gms.httpapi.service.AdminApiService;
import org.gms.observability.Metrics;
import org.gms.service.admin.AdminService;

/**
 * http-api 模块装配（架构 M3-1：镜像、限流、服务编排）。
 *
 * <p>只依赖 core + data（红线 4.1 / ArchUnit 规则 1）：经 {@link AdminService}（core 公共契约）
 * 访问频道，经 data repository 查 DB，经 {@link EventBus} 订阅在线事件维护只读镜像。
 */
@Factory
public class HttpApiConfig {

    @Bean
    @Singleton
    public OnlinePlayerMirror onlinePlayerMirror(EventBus eventBus) {
        return new OnlinePlayerMirror(eventBus);
    }

    @Bean
    @Singleton
    public ApiRateLimiter apiRateLimiter(
            @Property(name = "twinkle.http.api.rate-limit.capacity", defaultValue = "100") int capacity,
            @Property(name = "twinkle.http.api.rate-limit.refill-seconds", defaultValue = "1") int refillSeconds,
            Metrics metrics) {
        return new ApiRateLimiter(capacity, refillSeconds, metrics);
    }

    @Bean
    @Singleton
    public AdminApiService adminApiService(AccountRepository accountRepository,
                                           CharacterRepository characterRepository,
                                           AdminService adminService,
                                           OnlinePlayerMirror mirror) {
        return new AdminApiService(accountRepository, characterRepository, adminService, mirror);
    }
}
