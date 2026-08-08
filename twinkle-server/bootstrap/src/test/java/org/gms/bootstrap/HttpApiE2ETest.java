package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.gms.event.EventBus;
import org.gms.service.admin.OnlinePlayerEvents;
import org.junit.jupiter.api.Test;

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

    @Test
    void httpEndpointsAndRateLimit() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-httpapi-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-httpapi-script").toString();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.profile", "single",
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.port", "0",
                "twinkle.script.path", scriptDir,
                // 限流参数调小以便测试 429
                "twinkle.http.api.rate-limit.capacity", "2",
                "twinkle.http.api.rate-limit.refill-seconds", "600"))) {

            EmbeddedServer server = ctx.getBean(EmbeddedServer.class);
            server.start();
            int port = server.getPort();
            String base = "http://127.0.0.1:" + port;
            HttpClient client = HttpClient.newHttpClient();

            // ---- /internal/v1/health ----
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/internal/v1/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("healthy");

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
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/online")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(apiOnline.statusCode()).isEqualTo(200);
            assertThat(apiOnline.body()).contains("HttpHero");

            // ---- ① 查 DB：账号不存在 404 ----
            HttpResponse<String> missing = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/account/nobody")).GET().build(),
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
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/online")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> r2 = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/online")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> r3 = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/online")).GET().build(),
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
                "twinkle.script.path", scriptDir))) {

            EmbeddedServer server = ctx.getBean(EmbeddedServer.class);
            server.start();
            int port = server.getPort();
            String base = "http://127.0.0.1:" + port;
            HttpClient client = HttpClient.newHttpClient();

            // AI 对话（工具自动循环：在线统计 → 报表；无玩家 → 在线人数为 0）
            HttpResponse<String> chat = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/ai/chat"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"在线统计\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(chat.statusCode()).isEqualTo(200);
            assertThat(chat.body()).contains("AI 报表");

            // AI 结构化报表（POJO 自动解析）
            HttpResponse<String> report = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/ai/report/online")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(report.statusCode()).isEqualTo(200);
            assertThat(report.body()).contains("onlineCount");

            // 调用统计（观测）
            HttpResponse<String> usage = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/ai/usage")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(usage.statusCode()).isEqualTo(200);
            assertThat(usage.body()).contains("callCount");
        }
    }
}
