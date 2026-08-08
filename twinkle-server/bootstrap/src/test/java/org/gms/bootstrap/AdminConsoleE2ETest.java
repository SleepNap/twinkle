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
 * M5-1 验收：Web 控制台运维 API（架构 M5-1：/admin/v1/*）。
 *
 * <p>用 bootstrap 完整装配（单进程档，含频道注册钩子），起真实 EmbeddedServer，验证：
 * <ol>
 *   <li>/admin/v1/health、/admin/v1/channels（频道注册钩子生效）、/admin/v1/online（③只读镜像）。</li>
 *   <li>配置中心链路：GET /config 列表 → POST /config 热改 → 订阅者读到新值（版本+1）。</li>
 *   <li>运维操作：踢下线（② AdminService，在线 404/离线 404）、脚本重载（②，空目录 changed=0）、
 *       /restart 返回 202 + phase（不触发真实重启，编排由 L4RestartE2ETest 覆盖）。</li>
 * </ol>
 */
class AdminConsoleE2ETest {

    @Test
    void adminConsoleEndpoints() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-admin-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-admin-script").toString();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.profile", "single",
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.port", "0",
                "twinkle.net.channel.host", "127.0.0.1",
                "twinkle.admin.restart.exit", "false",  // 测试不真退出进程（编排由 L4RestartE2ETest 覆盖）
                "twinkle.script.path", scriptDir))) {

            EmbeddedServer server = ctx.getBean(EmbeddedServer.class);
            server.start();
            int port = server.getPort();
            String base = "http://127.0.0.1:" + port;
            HttpClient client = HttpClient.newHttpClient();

            // ---- /admin/v1/health ----
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("healthy");

            // ---- /admin/v1/channels（频道启动注册钩子生效：至少 1 频道）----
            HttpResponse<String> channels = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/channels")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(channels.statusCode()).isEqualTo(200);
            assertThat(channels.body()).contains("channelId").contains("127.0.0.1");

            // ---- /admin/v1/online（③ 只读镜像：手动推事件 → HTTP 读到）----
            EventBus bus = ctx.getBean(EventBus.class);
            bus.send(OnlinePlayerEvents.TARGET,
                    new OnlinePlayerEvents.PlayerOnline(888L, "AdminHero", 100000000, 10, 0));

            HttpResponse<String> online = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/online")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(online.statusCode()).isEqualTo(200);
            assertThat(online.body()).contains("AdminHero").contains("onlineCount");

            // ---- /admin/v1/reload/in-flight（在途实体可观测）----
            HttpResponse<String> inFlight = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/reload/in-flight")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(inFlight.statusCode()).isEqualTo(200);
            assertThat(inFlight.body()).contains("inFlightCount");

            // ---- 配置中心：GET 列表（含 V1 seed）----
            HttpResponse<String> cfgList = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/config")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cfgList.statusCode()).isEqualTo(200);
            assertThat(cfgList.body()).contains("game.level.rate").contains("version");

            // ---- 配置热改：POST → 订阅者重读新值（L1，走配置中心变更链路）----
            HttpResponse<String> cfgBefore = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/config"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"game.exp.rate\",\"value\":\"2.5\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(cfgBefore.statusCode()).isEqualTo(200);
            assertThat(cfgBefore.body()).contains("game.exp.rate").contains("2.5");

            org.gms.config.ConfigFacade config = ctx.getBean(org.gms.config.ConfigFacade.class);
            assertThat(config.get("game.exp.rate", String.class)).contains("2.5");
            assertThat(config.currentVersion()).isGreaterThan(0);

            // 非法 body → 400
            HttpResponse<String> badCfg = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/config"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"value\":\"x\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(badCfg.statusCode()).isEqualTo(400);

            // ---- 踢下线（② AdminService：镜像里 888 在线但会话未注册 → 404）----
            HttpResponse<String> kickOffline = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/kick"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"characterId\":888}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(kickOffline.statusCode()).isEqualTo(404);

            // 非法 body → 400
            HttpResponse<String> kickBad = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/kick"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"characterId\":\"x\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(kickBad.statusCode()).isEqualTo(400);

            // ---- 脚本重载（② AdminService.reloadScripts：空目录 changed=0）----
            HttpResponse<String> reloadScripts = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/reload/scripts"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(reloadScripts.statusCode()).isEqualTo(200);
            assertThat(reloadScripts.body()).contains("changed").contains("0");

            // ---- 逻辑重载（core 安全点重载：换代版本门）----
            HttpResponse<String> reloadLogic = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/reload/logic"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(reloadLogic.statusCode()).isEqualTo(200);
            assertThat(reloadLogic.body()).contains("newVersion");

            // ---- 重启（202 accepted + phase，不触发真实 System.exit）----
            HttpResponse<String> restart = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/restart"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(restart.statusCode()).isEqualTo(202);
            assertThat(restart.body()).contains("accepted").contains("phase");

            // ---- 重启阶段读取 ----
            HttpResponse<String> phase = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/restart/phase")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(phase.statusCode()).isEqualTo(200);
            assertThat(phase.body()).contains("phase");
        }
    }
}
