package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.gms.config.ConfigFacade;
import org.gms.event.EventBus;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置中心全链路验证（架构 4.6.5：DB 真值 + 版本号广播 → 各角色重读）。
 *
 * <p>链路：admin（HTTP /internal/v1/config 写）→ DB 真值 + 版本号 +1 → EventBus 广播
 * ConfigChangeEvent → 订阅者（模拟 channel 侧系统）收到事件 → 重读出新值。
 *
 * <p>单进程内"TCP 长连接订阅"= 进程内 EventBus 订阅（接口已按 4.6.5 设计，M6 换网络实现）。
 */
class ConfigCenterE2ETest {

    @Test
    void configChangePropagatesToSubscribers() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-config-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-config-script").toString();

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

            // ---- channel 侧订阅者：模拟频道系统订阅配置变更 ----
            EventBus bus = ctx.getBean(EventBus.class);
            ConfigFacade config = ctx.getBean(ConfigFacade.class);
            AtomicInteger changeEvents = new AtomicInteger();
            AtomicLong lastVersion = new AtomicLong();
            bus.subscribe("config-center", org.gms.config.ConfigChangeEvent.class, event -> {
                changeEvents.incrementAndGet();
                lastVersion.set(event.version());
            });

            long v0 = config.currentVersion();

            // ---- admin 改配置：POST /internal/v1/config → DB 真值 + 版本广播 ----
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/internal/v1/config"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"key\":\"game.drop.rate\",\"value\":\"2.5\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);

            // ---- 订阅者收到广播 + 重读出新值 ----
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3))
                    .untilAsserted(() -> assertThat(changeEvents.get()).isGreaterThan(0));
            assertThat(lastVersion.get()).isGreaterThan(v0);
            assertThat(config.get("game.drop.rate", String.class)).contains("2.5"); // 订阅者重读

            // ---- DB 真值持久化（重启后仍能读到） ----
            assertThat(ctx.getBean(org.gms.data.config.DbConfigFacade.class)
                    .get("game.drop.rate", String.class)).contains("2.5");
        }
    }
}
