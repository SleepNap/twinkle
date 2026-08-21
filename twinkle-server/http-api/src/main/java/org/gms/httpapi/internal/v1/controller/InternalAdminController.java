package org.gms.httpapi.internal.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import org.gms.data.config.DbConfigFacade;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.httpapi.application.admin.AdminApiService;
import org.gms.i18n.I18nService;
import org.gms.observability.HealthRegistry;
import org.gms.service.admin.AdminService;

import java.util.List;
import java.util.Map;

/**
 * 内部 API（架构 M3-1：/internal/v1/*，官网与内部系统转调）。
 *
 * <p>全部端点经 {@code ApiKeyAuthFilter} 认证、按最小 Scope 授权并写审计；写端点分别要求
 * {@code server.reload:write} 与 {@code server.config:write}。
 */
@Controller(ApiRoutes.INTERNAL_V1)
@Produces(MediaType.APPLICATION_JSON)
public final class InternalAdminController {

    private final AdminApiService adminApiService;
    private final HealthRegistry healthRegistry;
    private final EntityReloadService reloadService;
    private final EntityReloadCoordinator reloadCoordinator;
    private final DbConfigFacade configFacade;
    private final I18nService i18n;

    public InternalAdminController(AdminApiService adminApiService, HealthRegistry healthRegistry,
                                   EntityReloadService reloadService,
                                   EntityReloadCoordinator reloadCoordinator,
                                   DbConfigFacade configFacade, I18nService i18n) {
        this.adminApiService = adminApiService;
        this.healthRegistry = healthRegistry;
        this.reloadService = reloadService;
        this.reloadCoordinator = reloadCoordinator;
        this.configFacade = configFacade;
        this.i18n = i18n;
    }

    /** 健康检查（liveness + readiness 聚合）。 */
    @Get("/health")
    public Map<String, Object> health() {
        return Map.of(
                "healthy", healthRegistry.isHealthy(),
                "checks", healthRegistry.statuses());
    }

    /** 在线玩家列表（③ 只读镜像）。 */
    @Get("/online")
    public Map<String, Object> online() {
        List<AdminService.OnlinePlayer> players = adminApiService.onlinePlayers();
        return Map.of(
                "onlineCount", players.size(),
                "players", players);
    }

    /** 在途实体概览（架构 5.3 可观测：哪些实体在长操作中，重载会中断它们）。 */
    @Get("/reload/in-flight")
    public Map<String, Object> inFlight() {
        return Map.of(
                "inFlightCount", reloadCoordinator.inFlightCount(),
                "entities", reloadCoordinator.inFlightEntities());
    }

    /** 触发按实体渐进重载（架构 5.3：安全点直切 + 在途显式中断 + 换代版本门）。 */
    @Post("/reload")
    public HttpResponse<?> reload() {
        EntityReloadService.ReloadResult result = reloadService.reloadAllInFlight(id -> true);
        return HttpResponse.ok(Map.of(
                "safeSwitched", result.safeSwitched(),
                "interrupted", result.interrupted(),
                "newVersion", result.newVersion()));
    }

    /** 写配置（架构 4.6.5 配置中心：DB 真值 + 版本号广播）。body: {key, value}。 */
    @Post("/config")
    public HttpResponse<?> setConfig(@Body Map<String, String> body) {
        String key = body.get("key");
        String value = body.get("value");
        if (key == null || key.isBlank() || value == null) {
            return HttpResponse.badRequest(Map.of("error", i18n.message("admin.error.config_required")));
        }
        configFacade.upsert(key, value); // 写 DB → 版本号 +1 → 广播 ConfigChangeEvent
        return HttpResponse.ok(Map.of(
                "key", key,
                "value", value,
                "version", configFacade.currentVersion()));
    }
}
