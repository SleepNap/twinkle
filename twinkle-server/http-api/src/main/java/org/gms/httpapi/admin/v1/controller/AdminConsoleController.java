package org.gms.httpapi.admin.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import org.gms.data.config.DbConfigFacade;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.httpapi.application.admin.AdminApiService;
import org.gms.httpapi.admin.AdminAuthFilter;
import org.gms.i18n.I18nService;
import org.gms.observability.HealthRegistry;
import org.gms.service.admin.AdminService;
import org.gms.service.channel.ChannelLifecycleService;
import org.gms.service.network.GameNetworkService;
import org.gms.service.shutdown.ClusterShutdownService;

import java.util.List;
import java.util.Map;

/**
 * Web 控制台运维 API（架构 M5-1：/admin/v1/*，Web 控制台后端）。
 *
 * <p>能力覆盖任务文档第 1 节"Web 控制台"的后端面：
 * <ul>
 *   <li><b>频道状态与启停</b>：{@code /admin/v1/channels}（经 {@link ChannelLifecycleService}）。</li>
 *   <li><b>在线玩家</b>：{@code GET /admin/v1/online}（③ 只读镜像）。</li>
 *   <li><b>配置热改</b>：{@code GET/POST /admin/v1/config}（走配置中心变更链路，DB 真值 + 版本广播）。</li>
 *   <li><b>运维操作</b>：踢下线 / 脚本重载 / 逻辑重载 / L4 重启——一律经 service 接口或 core 契约，
 *       管理侧不直踩游戏内存（红线 4.1）。</li>
 * </ul>
 *
 * <p>与 {@code /internal/v1} 同待遇：内网/loopback 绑定（红线 20 网络平面收敛），不套
 * {@code ApiRateLimitFilter}（该过滤器只匹配 {@code /api/v1}）。强鉴权留后续安全里程碑。
 */
@Controller(ApiRoutes.ADMIN_V1)
@Produces(MediaType.APPLICATION_JSON)
public final class AdminConsoleController {

    private final AdminApiService adminApiService;
    private final AdminService adminService;
    private final ChannelLifecycleService channelLifecycleService;
    private final ClusterShutdownService clusterShutdownService;
    private final HealthRegistry healthRegistry;
    private final EntityReloadService reloadService;
    private final EntityReloadCoordinator reloadCoordinator;
    private final DbConfigFacade configFacade;
    private final I18nService i18n;
    private final GameNetworkService gameNetworkService;

    public AdminConsoleController(AdminApiService adminApiService, AdminService adminService,
                                  ChannelLifecycleService channelLifecycleService,
                                  ClusterShutdownService clusterShutdownService,
                                  HealthRegistry healthRegistry,
                                  EntityReloadService reloadService,
                                  EntityReloadCoordinator reloadCoordinator,
                                  DbConfigFacade configFacade, I18nService i18n,
                                  GameNetworkService gameNetworkService) {
        this.adminApiService = adminApiService;
        this.adminService = adminService;
        this.channelLifecycleService = channelLifecycleService;
        this.clusterShutdownService = clusterShutdownService;
        this.healthRegistry = healthRegistry;
        this.reloadService = reloadService;
        this.reloadCoordinator = reloadCoordinator;
        this.configFacade = configFacade;
        this.i18n = i18n;
        this.gameNetworkService = gameNetworkService;
    }

    // ---- 频道状态 / 在线玩家 / 健康 ----

    /** 健康检查（liveness + readiness 聚合）。 */
    @Get("/health")
    public Map<String, Object> health() {
        return Map.of(
                "healthy", healthRegistry.isHealthy(),
                "checks", healthRegistry.statuses());
    }

    /** 频道状态列表；single 读本地监听，split 经 coordinator RPC 汇总频道工作进程。 */
    @Get("/channels")
    public Map<String, Object> channels() {
        return Map.of("channels", channelLifecycleService.statuses());
    }

    /** 异步启动指定频道的玩家监听。 */
    @Post("/channels/{channelId}/start")
    public HttpResponse<?> startChannel(@PathVariable int channelId) {
        ChannelLifecycleService.CommandResult result = channelLifecycleService.requestStart(channelId);
        return (result.accepted() ? HttpResponse.accepted() : HttpResponse.status(HttpStatus.CONFLICT))
                .body(result);
    }

    /** 异步安全停止指定频道的玩家监听，频道工作进程和控制链路保持在线。 */
    @Post("/channels/{channelId}/stop{?force}")
    public HttpResponse<?> stopChannel(@PathVariable int channelId,
                                       @QueryValue(defaultValue = "false") boolean force,
                                       HttpRequest<?> request) {
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE, "force=" + force);
        ChannelLifecycleService.CommandResult result = channelLifecycleService.requestStop(channelId, force);
        return (result.accepted() ? HttpResponse.accepted() : HttpResponse.status(HttpStatus.CONFLICT))
                .body(result);
    }

    /** 安全存档后退出指定频道工作进程。 */
    @Post("/channels/{channelId}/terminate{?force}")
    public HttpResponse<?> terminateChannel(@PathVariable int channelId,
                                            @QueryValue(defaultValue = "false") boolean force,
                                            HttpRequest<?> request) {
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE, "force=" + force);
        ChannelLifecycleService.CommandResult result = channelLifecycleService.requestTerminate(channelId, force);
        return (result.accepted() ? HttpResponse.accepted() : HttpResponse.status(HttpStatus.CONFLICT))
                .body(result);
    }

    /** 两阶段关闭整个集群：排空频道、退出频道进程，最后退出管理进程。 */
    @Post("/cluster/shutdown{?force}")
    public HttpResponse<?> shutdownCluster(@QueryValue(defaultValue = "false") boolean force,
                                           HttpRequest<?> request) {
        request.setAttribute(AdminAuthFilter.AFTER_SUMMARY_ATTRIBUTE, "force=" + force);
        ClusterShutdownService.CommandResult result = clusterShutdownService.requestShutdown(force);
        return (result.accepted() ? HttpResponse.accepted() : HttpResponse.status(HttpStatus.CONFLICT))
                .body(result);
    }

    /** 集群关停阶段；部分失败时管理 HTTP 保持在线供诊断和重试。 */
    @Get("/cluster/shutdown/status")
    public ClusterShutdownService.Status clusterShutdownStatus() {
        return clusterShutdownService.status();
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

    // ---- 配置中心（L1 热改：DB 真值 + 版本广播） ----

    /** 全部配置项（① 查 DB 真值快照）。 */
    @Get("/config")
    public Map<String, Object> configList() {
        return Map.of(
                "version", configFacade.currentVersion(),
                "configs", configFacade.snapshot());
    }

    /** 单个配置项（① 查 DB）。不存在 404。 */
    @Get("/config/{key}")
    public HttpResponse<?> configGet(@PathVariable String key) {
        return configFacade.get(key, String.class)
                .<HttpResponse<?>>map(value -> HttpResponse.ok(Map.of("key", key, "value", value)))
                .orElseGet(() -> HttpResponse.notFound(Map.of("error", "config_not_found")));
    }

    /** 写配置（架构 4.6.5 配置中心：写 DB → 版本号 +1 → 广播 ConfigChangeEvent）。body: {key, value}。 */
    @Post("/config")
    public HttpResponse<?> configSet(@Body Map<String, String> body) {
        String key = body.get("key");
        String value = body.get("value");
        if (key == null || key.isBlank() || value == null) {
            return HttpResponse.badRequest(Map.of("error", i18n.message("admin.error.config_required")));
        }
        configFacade.upsert(key, value);
        return HttpResponse.ok(Map.of(
                "key", key,
                "value", value,
                "version", configFacade.currentVersion()));
    }

    // ---- 运维操作（② 经 service 接口 / core 契约） ----

    /** 踢下线（② AdminService.kick）。body: {characterId}。 */
    @Post("/kick")
    public HttpResponse<?> kick(@Body Map<String, Object> body) {
        Object raw = body.get("characterId");
        if (!(raw instanceof Number n)) {
            return HttpResponse.badRequest(Map.of("error", i18n.message("admin.error.character_id_required")));
        }
        long characterId = n.longValue();
        boolean kicked = adminService.kick(characterId);
        if (kicked) {
            return HttpResponse.ok(Map.of("kicked", true, "characterId", characterId));
        }
        return HttpResponse.notFound(Map.of("error", "character_not_online", "characterId", characterId));
    }

    /** 逻辑重载（架构 5.3：安全点直切 + 在途显式中断 + 换代版本门）。 */
    @Post("/reload/logic")
    public HttpResponse<?> reloadLogic() {
        EntityReloadService.ReloadResult result = reloadService.reloadAllInFlight(id -> true);
        return HttpResponse.ok(Map.of(
                "safeSwitched", result.safeSwitched(),
                "interrupted", result.interrupted(),
                "newVersion", result.newVersion()));
    }

    /** 脚本重载（L2 热重载，② AdminService.reloadScripts）。 */
    @Post("/reload/scripts")
    public Map<String, Object> reloadScripts() {
        return Map.of("changed", adminService.reloadScripts());
    }

    /** 全量 WZ 热重载；注册资源先全部准备成功，再统一换代。 */
    @Post("/reload/wz")
    public AdminService.WzReloadResult reloadWz() {
        return adminService.reloadWz();
    }

    /** 请求一次主动重启（L4，② AdminService.requestRestart，异步编排）。 */
    @Post("/restart")
    public HttpResponse<?> restart() {
        adminService.requestRestart();
        return HttpResponse.status(HttpStatus.ACCEPTED).body(Map.of(
                "accepted", true,
                "phase", adminService.restartPhase().name()));
    }

    /** 当前重启编排阶段（监控面板轮询展示用）。 */
    @Get("/restart/phase")
    public Map<String, Object> restartPhase() {
        return Map.of("phase", adminService.restartPhase().name());
    }

    /** 仅重启游戏 Netty（登录服与本地频道服），Micronaut HTTP 保持在线。 */
    @Post("/restart/netty")
    public HttpResponse<?> restartNetty() {
        boolean accepted = gameNetworkService.requestRestart();
        GameNetworkService.Status status = gameNetworkService.status();
        return (accepted ? HttpResponse.accepted() : HttpResponse.status(HttpStatus.CONFLICT)).body(Map.of(
                "accepted", accepted,
                "status", status));
    }

    /** 游戏 Netty 独立生命周期状态。 */
    @Get("/restart/netty/status")
    public GameNetworkService.Status restartNettyStatus() {
        return gameNetworkService.status();
    }
}
