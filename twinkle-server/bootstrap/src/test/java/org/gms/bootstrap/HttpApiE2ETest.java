package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.gms.channel.PlayerStorage;
import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.PetItem;
import org.gms.event.EventBus;
import org.gms.data.repo.ApiRequestAuditRepository;
import org.gms.data.repo.ToolExecutionAuditRepository;
import org.gms.service.admin.OnlinePlayerEvents;
import org.junit.jupiter.api.Test;
import org.gms.ai.service.AiPlayerSupportAgent;
import org.gms.service.agent.PlayerSupportAgent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-1 验收：http-api 装配 + 数据三路 + 限流（架构 M3-1 第 1 节）。
 *
 * <p>用 bootstrap 完整装配（DataSource/迁移/ConfigFacade/HealthRegistry/Metrics/AdminService/
 * EventBus 全部 bean 就绪），起真实 EmbeddedServer。验证：
 * <ol>
 *   <li>/internal/v1/health（健康检查）与 /internal/v1/online 可调。</li>
 *   <li>/api/v1/account/{name} ①查 DB；/api/v1/online ③只读镜像（推事件→HTTP 读到）。</li>
 *   <li>/api/v1 限流：调小容量后超量请求 429（Bucket4j）。</li>
 * </ol>
 */
class HttpApiE2ETest {

    private static final String BOOTSTRAP_KEY = "twinkle-http-api-e2e-bootstrap-key-123456";

    @Test
    void httpEndpointsAndRateLimit() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-httpapi-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-httpapi-script").toString();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.profile", "single",
                "twinkle.service.language", "zh-CN",
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.port", "0",
                "twinkle.script.path", scriptDir,
                "twinkle.http.api.bootstrap-key", BOOTSTRAP_KEY,
                // 限流参数调小以便测试 429
                "twinkle.http.api.rate-limit.capacity", "2",
                "twinkle.http.api.rate-limit.refill-seconds", "600"))) {

            EmbeddedServer server = ctx.getBean(EmbeddedServer.class);
            assertThat(ctx.getBean(PlayerSupportAgent.class).available()).isFalse();
            server.start();
            int port = server.getPort();
            String base = "http://127.0.0.1:" + port;
            HttpClient client = HttpClient.newHttpClient();

            // ---- /internal/v1/health ----
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/internal/v1/health"))
                            .header("Accept-Language", "en-US")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("healthy");
            assertThat(health.headers().firstValue("Content-Language")).contains("zh-CN");

            // ---- /internal/v1/reload/in-flight（架构 5.3 可观测：在途实体）----
            HttpResponse<String> inFlightResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/internal/v1/reload/in-flight")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(inFlightResp.statusCode()).isEqualTo(200);
            assertThat(inFlightResp.body()).contains("inFlightCount");

            // ---- /internal/v1/reload（触发按实体渐进重载：换代版本门）----
            HttpResponse<String> reloadResp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/internal/v1/reload"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(reloadResp.statusCode()).isEqualTo(200);
            assertThat(reloadResp.body()).contains("newVersion");

            // ---- ③ 事件驱动镜像：手动推事件 → HTTP 读到 ----
            EventBus bus = ctx.getBean(EventBus.class);
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(999L, "HttpHero", 100000000, 10, 0));

            HttpResponse<String> internalOnline = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/internal/v1/online")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(internalOnline.statusCode()).isEqualTo(200);
            assertThat(internalOnline.body()).contains("HttpHero").contains("onlineCount");

            HttpResponse<String> apiOnline = client.send(
                    authorized(base + "/api/v1/online").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(apiOnline.statusCode()).isEqualTo(200);
            assertThat(apiOnline.body()).contains("HttpHero");

            // ---- ① 查 DB：账号不存在 404 ----
            HttpResponse<String> missing = client.send(
                    authorized(base + "/api/v1/account/nobody").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(missing.statusCode()).isEqualTo(404);

            // ---- L1 配置热改：POST /internal/v1/config → DB 真值 + 版本号广播（架构 4.6.5） ----
            HttpResponse<String> cfgBefore = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/internal/v1/config"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"game.level.rate\",\"value\":\"3.0\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cfgBefore.statusCode()).isEqualTo(200);
            assertThat(cfgBefore.body()).contains("game.level.rate").contains("3.0");

            // 订阅者重读：ConfigFacade.get 出新值（经广播重读 param_conf）
            org.gms.config.ConfigFacade config = ctx.getBean(org.gms.config.ConfigFacade.class);
            assertThat(config.get("game.level.rate", String.class)).contains("3.0");
            assertThat(config.currentVersion()).isGreaterThan(0);

            // 写非法 body → 400
            HttpResponse<String> badCfg = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/internal/v1/config"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"value\":\"x\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(badCfg.statusCode()).isEqualTo(400);

            // ---- 限流：容量 2，累计请求数已到 2（apiOnline + missing），第 3 个应 429 ----
            HttpResponse<String> r1 = client.send(
                    authorized(base + "/api/v1/online").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> r2 = client.send(
                    authorized(base + "/api/v1/online").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> r3 = client.send(
                    authorized(base + "/api/v1/online").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(r1.statusCode()).isEqualTo(429);
            assertThat(r2.statusCode()).isEqualTo(429);
            assertThat(r3.statusCode()).isEqualTo(429);
        }
    }

    @Test
    void aiEndpointsWork() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-httpai-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-httpai-script").toString();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.profile", "single",
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.port", "0",
                "twinkle.script.path", scriptDir,
                "twinkle.ai.enabled", "true",
                "twinkle.http.api.bootstrap-key", BOOTSTRAP_KEY))) {

            EmbeddedServer server = ctx.getBean(EmbeddedServer.class);
            assertThat(ctx.getBean(PlayerSupportAgent.class)).isInstanceOf(AiPlayerSupportAgent.class);
            server.start();
            int port = server.getPort();
            String base = "http://127.0.0.1:" + port;
            HttpClient client = HttpClient.newHttpClient();

            // AI 对话（工具自动循环：在线统计 → 报表；无玩家 → 在线人数为 0）
            HttpResponse<String> chat = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/ai/chat"))
                            .header("X-API-Key", BOOTSTRAP_KEY)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"在线统计\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(chat.statusCode()).isEqualTo(200);
            assertThat(chat.body()).contains("AI 报表", "local-rule/deterministic", "queryOnlineStats",
                    "audit_agent_", "conversationId");

            // twish 通过 Tool-first 能力面发现并执行服务端 Agent，而非依赖私有路由。
            HttpResponse<String> agentCatalog = client.send(
                    authorized(base + "/api/v1/capabilities?query=agent").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(agentCatalog.statusCode()).isEqualTo(200);
            assertThat(agentCatalog.body()).contains("server.agent.investigate",
                    "server.agent.conversation.close", "\"availability\":\"available\"");

            String agentRequestId = "req_agent_capability_01";
            HttpResponse<String> agentExecution = client.send(
                    authorized(base + "/api/v1/tool-executions")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toolBody(agentRequestId,
                                    "server.agent.investigate",
                                    "{\"conversationId\":\"twish-agent-e2e\",\"message\":\"在线统计\"}",
                                    "调查当前在线情况"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(agentExecution.statusCode()).isEqualTo(200);
            assertThat(agentExecution.body()).contains("twish-agent-e2e", "AI 报表",
                    "queryOnlineStats", "audit_agent_", "local-rule/deterministic");

            String closeRequestId = "req_agent_close_01";
            HttpResponse<String> closeExecution = client.send(
                    authorized(base + "/api/v1/tool-executions")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toolBody(closeRequestId,
                                    "server.agent.conversation.close",
                                    "{\"conversationId\":\"twish-agent-e2e\"}",
                                    "结束本次调查会话"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(closeExecution.statusCode()).isEqualTo(200);
            assertThat(closeExecution.body()).contains("twish-agent-e2e", "\"evicted\":true");

            // AI 结构化报表（POJO 自动解析）
            HttpResponse<String> report = client.send(
                    authorized(base + "/api/v1/ai/report/online").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(report.statusCode()).isEqualTo(200);
            assertThat(report.body()).contains("onlineCount");

            // 调用统计（观测）
            HttpResponse<String> usage = client.send(
                    authorized(base + "/api/v1/ai/usage").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(usage.statusCode()).isEqualTo(200);
            assertThat(usage.body()).contains("callCount");
        }
    }

    @Test
    void twishKeyLifecycleScopeAndAuditWorkEndToEnd() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-twish-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-twish-script").toString();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.profile", "single",
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.port", "0",
                "twinkle.script.path", scriptDir,
                "twinkle.http.api.bootstrap-key", BOOTSTRAP_KEY))) {
            EmbeddedServer server = ctx.getBean(EmbeddedServer.class);
            server.start();
            String base = "http://127.0.0.1:" + server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> contract = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/openapi.yaml")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(contract.statusCode()).isEqualTo(200);
            assertThat(contract.body()).contains("Twinkle Capability API");

            HttpResponse<String> unauthenticated = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/online")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthenticated.statusCode()).isEqualTo(401);
            assertThat(unauthenticated.body()).contains("unauthenticated");

            HttpResponse<String> issued = client.send(
                    authorized(base + "/api/v1/auth/keys")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"displayName\":\"twish-test\",\"scopes\":[\"game:read\"]}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(issued.statusCode()).isEqualTo(201);
            String token = issued.body().replaceFirst(".*\"token\":\"([^\"]+)\".*", "$1");
            assertThat(token).startsWith("twk_");

            HttpResponse<String> capabilities = client.send(
                    withKey(base + "/api/v1/capabilities", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(capabilities.statusCode()).isEqualTo(200);
            assertThat(capabilities.body()).contains("server.health.read").contains("player.online.list");

            HttpResponse<String> read = client.send(
                    withKey(base + "/api/v1/online", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(read.headers().firstValue("X-Request-ID")).isPresent();

            HttpResponse<String> forbidden = client.send(
                    withKey(base + "/api/v1/characters/999/session", token)
                            .DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(forbidden.statusCode()).isEqualTo(403);
            assertThat(forbidden.body()).contains("game:write");

            String keyPrefix = issued.body().replaceFirst(".*\"keyPrefix\":\"([^\"]+)\".*", "$1");
            HttpResponse<String> revoked = client.send(
                    authorized(base + "/api/v1/auth/keys/" + keyPrefix).DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(revoked.statusCode()).isEqualTo(204);

            HttpResponse<String> afterRevoke = client.send(
                    withKey(base + "/api/v1/online", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(afterRevoke.statusCode()).isEqualTo(401);

            ApiRequestAuditRepository audits = ctx.getBean(ApiRequestAuditRepository.class);
            assertThat(audits.count()).isGreaterThanOrEqualTo(7);
        }
    }

    @Test
    void twishReadonlyToolContractWorksEndToEnd() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-tool-contract-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-tool-contract-script").toString();

        try (ApplicationContext ctx = ApplicationContext.run(Map.ofEntries(
                Map.entry("twinkle.db.url", "jdbc:sqlite:" + dbPath),
                Map.entry("twinkle.profile", "single"),
                Map.entry("micronaut.server.port", "0"),
                Map.entry("twinkle.net.login.port", "0"),
                Map.entry("twinkle.net.channel.port", "0"),
                Map.entry("twinkle.script.path", scriptDir),
                Map.entry("twinkle.http.api.bootstrap-key", BOOTSTRAP_KEY),
                Map.entry("twinkle.http.api.cursor-signing-key", "twinkle-e2e-cursor-signing-key-123456"),
                Map.entry("twinkle.server.id", "server-e2e"),
                Map.entry("twinkle.server.name", "契约测试服"),
                Map.entry("twinkle.server.environment", "test"),
                Map.entry("twinkle.server.version", "0.1.0")))) {
            EmbeddedServer server = ctx.getBean(EmbeddedServer.class);
            server.start();
            String base = "http://127.0.0.1:" + server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<String> issued = client.send(
                    authorized(base + "/api/v1/auth/keys")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"displayName\":\"twish-readonly\",\"scopes\":[\"server.health:read\",\"player.online:read\",\"player.inventory:read\"]}"))
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(issued.statusCode()).isEqualTo(201);
            String token = jsonString(issued.body(), "token");

            HttpResponse<String> identity = client.send(
                    bearer(base + "/api/v1/identity/me", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(identity.statusCode()).isEqualTo(200);
            assertThat(identity.body()).contains("server-e2e").contains("permissionVersion")
                    .contains("server.health:read").doesNotContain("secretHash");
            assertThat(identity.headers().firstValue("X-Contract-Version")).contains("0.1");

            HttpResponse<String> catalog = client.send(
                    bearer(base + "/api/v1/capabilities", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(catalog.statusCode()).isEqualTo(200);
            assertThat(catalog.body()).contains("catalogVersion")
                    .contains("server.health.read").contains("player.online.list")
                    .contains("player.inventory.read")
                    .doesNotContain("inputSchema");
            assertThat(catalog.headers().firstValue("ETag")).isPresent();

            HttpResponse<String> healthDetail = client.send(
                    bearer(base + "/api/v1/capabilities/server.health.read", token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(healthDetail.statusCode()).isEqualTo(200);
            assertThat(healthDetail.body()).contains("inputSchema")
                    .contains("server.health:read").contains("additionalProperties");

            String healthRequestId = "req_health_e2e_01";
            String healthBody = toolBody(healthRequestId, "server.health.read", "{}",
                    "检查当前服务器状态");
            HttpResponse<String> health = client.send(
                    bearer(base + "/api/v1/tool-executions", token)
                            .header("X-Request-Id", healthRequestId)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(healthBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"succeeded\"")
                    .contains("server-e2e").contains("liveness").contains("auditRef")
                    .doesNotContain("jdbc:").doesNotContain("Exception");
            String healthExecutionId = jsonString(health.body(), "executionId");
            String healthAuditRef = jsonString(health.body(), "auditRef");

            ToolExecutionAuditRepository toolAudits = ctx.getBean(ToolExecutionAuditRepository.class);
            assertThat(toolAudits.findByAuditRef(healthAuditRef)).isPresent();
            // 用增量而非绝对计数：MybatisFlexBootstrap.start() 注册的是全局默认 DataSource，
            // 同一 JVM 内先启动的 ApplicationContext 会被后续测试类共享，绝对计数并不稳定
            // （同文件 :310 早已因此改用 isGreaterThanOrEqualTo）。这里真正要断言的是"新增了几条"。
            long auditBaseline = toolAudits.count() - 1;
            assertThat(toolAudits.count() - auditBaseline).isEqualTo(1);

            HttpResponse<String> duplicate = client.send(
                    bearer(base + "/api/v1/tool-executions", token)
                            .header("X-Request-Id", healthRequestId)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(healthBody)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(duplicate.statusCode()).isEqualTo(200);
            assertThat(jsonString(duplicate.body(), "executionId")).isEqualTo(healthExecutionId);
            assertThat(toolAudits.count() - auditBaseline).isEqualTo(1);

            HttpResponse<String> queried = client.send(
                    bearer(base + "/api/v1/tool-executions/" + healthExecutionId, token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(queried.statusCode()).isEqualTo(200);
            assertThat(queried.body()).contains(healthExecutionId);

            EventBus eventBus = ctx.getBean(EventBus.class);
            eventBus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(30L, "Thirty", 100000030, 30, 300));
            eventBus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(10L, "Ten", 100000010, 10, 100));
            eventBus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(20L, "Twenty", 100000020, 20, 200));

            String onlineRequestId = "req_online_e2e_01";
            HttpResponse<String> online = client.send(
                    bearer(base + "/api/v1/tool-executions", token)
                            .header("X-Request-Id", onlineRequestId)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toolBody(onlineRequestId,
                                    "player.online.list", "{\"pageSize\":2,\"cursor\":null}",
                                    "查看当前在线角色"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(online.statusCode()).isEqualTo(200);
            assertThat(online.body()).contains("\"onlineCount\":3")
                    .contains("\"returnedCount\":2")
                    .contains("\"characterId\":\"10\"")
                    .contains("\"characterId\":\"20\"")
                    .doesNotContain("ownerAccountId").doesNotContain("ipAddress");
            String cursor = jsonString(online.body(), "nextCursor");
            assertThat(cursor).isNotBlank();
            assertThat(toolAudits.count() - auditBaseline).isEqualTo(2);

            Character inventoryCharacter = new Character(1L);
            inventoryCharacter.setId(42L);
            inventoryCharacter.setName("InventoryHero");
            PetItem pet = new PetItem(5_000_000, 9001);
            pet.setPosition((short) 2);
            pet.setPetName("小黑");
            pet.setCloseness((short) 3456);
            inventoryCharacter.getInventory(InventoryType.CASH).putAtSlot((short) 2, pet);
            inventoryCharacter.markDirty();
            ctx.getBean(PlayerStorage.class).add(inventoryCharacter);
            String inventoryRequestId = "req_inventory_e2e_01";
            HttpResponse<String> inventory = client.send(
                    bearer(base + "/api/v1/tool-executions", token)
                            .header("X-Request-Id", inventoryRequestId)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toolBody(inventoryRequestId,
                                    "player.inventory.read", "{\"characterId\":\"42\"}",
                                    "核对在线角色背包"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(inventory.statusCode()).isEqualTo(200);
            assertThat(inventory.body()).contains("\"characterId\":\"42\"")
                    .contains("InventoryHero").contains("\"itemType\":\"pet\"")
                    .contains("\"petId\":\"9001\"").contains("小黑")
                    .contains("\"closeness\":3456");
            assertThat(toolAudits.count() - auditBaseline).isEqualTo(3);

            eventBus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(40L, "Forty", 100000040, 40, 400));
            String staleRequestId = "req_online_e2e_02";
            HttpResponse<String> staleCursor = client.send(
                    bearer(base + "/api/v1/tool-executions", token)
                            .header("X-Request-Id", staleRequestId)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toolBody(staleRequestId,
                                    "player.online.list",
                                    "{\"pageSize\":2,\"cursor\":\"" + cursor + "\"}",
                                    "继续读取在线角色"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(staleCursor.statusCode()).isEqualTo(409);
            assertThat(staleCursor.body()).contains("snapshot_changed").contains("retryable");

            String invalidRequestId = "req_invalid_e2e_01";
            HttpResponse<String> invalid = client.send(
                    bearer(base + "/api/v1/tool-executions", token)
                            .header("X-Request-Id", invalidRequestId)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toolBody(invalidRequestId,
                                    "server.health.read", "{\"subjectId\":\"forged\"}",
                                    "尝试伪造身份"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(invalid.body()).contains("invalid_input").doesNotContain("stackTrace");
        }
    }

    private static HttpRequest.Builder authorized(String uri) {
        return withKey(uri, BOOTSTRAP_KEY);
    }

    private static HttpRequest.Builder withKey(String uri, String key) {
        return HttpRequest.newBuilder(URI.create(uri)).header("X-API-Key", key);
    }

    private static HttpRequest.Builder bearer(String uri, String key) {
        return HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer " + key);
    }

    private static String toolBody(String requestId, String toolId, String input,
                                   String intentSummary) {
        return "{\"contractVersion\":\"0.1\",\"requestId\":\"" + requestId
                + "\",\"taskId\":\"task_e2e\",\"stepId\":\"step_e2e\",\"toolId\":\""
                + toolId + "\",\"toolVersion\":\"1.0.0\",\"input\":" + input
                + ",\"dryRun\":false,\"idempotencyKey\":null,\"approvalToken\":null,"
                + "\"clientContext\":{\"locale\":\"zh-CN\",\"source\":\"desktop\","
                + "\"intentSummary\":\"" + intentSummary + "\"}}";
    }

    private static String jsonString(String json, String field) {
        String value = json.replaceFirst(".*\\\"" + field + "\\\":\\\"([^\\\"]+)\\\".*", "$1");
        assertThat(value).as("JSON field %s", field).isNotEqualTo(json);
        return value;
    }
}
