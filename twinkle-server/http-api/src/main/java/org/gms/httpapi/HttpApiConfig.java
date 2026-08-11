package org.gms.httpapi;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.ApiKeyRepository;
import org.gms.data.repo.ApiRequestAuditRepository;
import org.gms.data.repo.ToolExecutionAuditRepository;
import org.gms.data.repo.CharacterRepository;
import org.gms.event.EventBus;
import org.gms.httpapi.limit.ApiRateLimiter;
import org.gms.httpapi.auth.ApiAccessPolicy;
import org.gms.httpapi.auth.ApiAuditService;
import org.gms.httpapi.auth.ApiKeyService;
import org.gms.httpapi.mirror.OnlinePlayerMirror;
import org.gms.httpapi.service.AdminApiService;
import org.gms.httpapi.identity.ServerIdentity;
import org.gms.httpapi.capability.ToolCatalogService;
import org.gms.httpapi.execution.OnlinePlayerPageService;
import org.gms.httpapi.execution.ServerHealthTool;
import org.gms.httpapi.execution.ToolExecutionService;
import org.gms.service.agent.ServerAgentService;
import org.gms.observability.Metrics;
import org.gms.observability.HealthRegistry;
import org.gms.role.ManagementProcessCondition;
import org.gms.service.admin.AdminService;

/**
 * http-api 模块装配（架构 M3-1：镜像、限流、服务编排）。
 *
 * <p>只依赖 core + data（红线 4.1 / ArchUnit 规则 1）：经 {@link AdminService}（core 公共契约）
 * 访问频道，经 data repository 查 DB，经 {@link EventBus} 订阅在线事件维护只读镜像。
 *
 * <p>管理进程专属（single 全内嵌；split 下仅 coordinator 角色装配，频道进程不启 HTTP 管理面）。
 */
@Factory
@Requires(condition = ManagementProcessCondition.class)
public class HttpApiConfig {

    @Bean
    @Singleton
    public ServerIdentity serverIdentity(
            @Property(name = "twinkle.server.id", defaultValue = "twinkle-local") String serverId,
            @Property(name = "twinkle.server.name", defaultValue = "twinkle") String displayName,
            @Property(name = "twinkle.server.environment", defaultValue = "development") String environment,
            @Property(name = "twinkle.server.version", defaultValue = "") String version) {
        return new ServerIdentity(serverId, displayName, environment, version);
    }

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

    @Bean
    @Singleton
    public ApiAccessPolicy apiAccessPolicy() {
        return new ApiAccessPolicy();
    }

    @Bean
    @Singleton
    public ApiKeyService apiKeyService(ApiKeyRepository repository,
                                       @Property(name = "twinkle.http.api.bootstrap-key", defaultValue = "")
                                       String bootstrapKey,
                                       ServerIdentity serverIdentity) {
        return new ApiKeyService(repository, bootstrapKey, serverIdentity);
    }

    @Bean
    @Singleton
    public ApiAuditService apiAuditService(ApiRequestAuditRepository repository) {
        return new ApiAuditService(repository);
    }

    @Bean
    @Singleton
    public ToolCatalogService toolCatalogService(ServerIdentity serverIdentity,
                                                 ServerAgentService serverAgentService) {
        return new ToolCatalogService(serverIdentity, serverAgentService);
    }

    @Bean
    @Singleton
    public ServerHealthTool serverHealthTool(HealthRegistry healthRegistry,
                                             ServerIdentity serverIdentity) {
        return new ServerHealthTool(healthRegistry, serverIdentity);
    }

    @Bean
    @Singleton
    public OnlinePlayerPageService onlinePlayerPageService(
            OnlinePlayerMirror mirror, ServerIdentity serverIdentity,
            @Property(name = "twinkle.http.api.cursor-signing-key", defaultValue = "")
            String cursorSigningKey) {
        return new OnlinePlayerPageService(mirror, serverIdentity, cursorSigningKey);
    }

    @Bean
    @Singleton
    public ToolExecutionService toolExecutionService(
            ToolCatalogService catalogService, ServerHealthTool healthTool,
            OnlinePlayerPageService onlineTool, ToolExecutionAuditRepository auditRepository,
            ApiRateLimiter rateLimiter, Metrics metrics, ServerIdentity serverIdentity,
            ServerAgentService serverAgentService) {
        return new ToolExecutionService(catalogService, healthTool, onlineTool, auditRepository,
                rateLimiter, metrics, serverIdentity, serverAgentService);
    }
}
