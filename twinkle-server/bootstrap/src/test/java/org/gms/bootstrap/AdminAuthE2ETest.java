package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.gms.data.entity.Account;
import org.gms.data.entity.AccountAdminRole;
import org.gms.data.entity.AdminRole;
import org.gms.data.repo.AccountAdminRoleRepository;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AdminOperationAuditRepository;
import org.gms.data.repo.AdminRoleRepository;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 强鉴权验收：/admin/v1 未认证 401、登录失败 401、登录成功、写操作缺 reason 400、RBAC 越权 403、审计落库。
 */
class AdminAuthE2ETest {

    @Test
    void adminAuthFlow() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-admin-auth-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-admin-auth-script").toString();

        try (ApplicationContext ctx = ApplicationContext.run(Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.profile", "single",
                "micronaut.server.port", "0",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.port", "0",
                "twinkle.net.channel.host", "127.0.0.1",
                "twinkle.admin.restart.exit", "false",
                "twinkle.script.path", scriptDir,
                "twinkle.http.admin.bootstrap-account", "admin",
                "twinkle.http.admin.bootstrap-password", "admin123"))) {

            EmbeddedServer server = ctx.getBean(EmbeddedServer.class);
            server.start();
            int port = server.getPort();
            String base = "http://127.0.0.1:" + port;
            HttpClient client = HttpClient.newHttpClient();

            // 未认证 → 401
            HttpResponse<String> noAuth = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(noAuth.statusCode()).isEqualTo(401);

            // 登录失败（错误密码）→ 401
            HttpResponse<String> badLogin = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/auth/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"admin\",\"password\":\"wrong\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(badLogin.statusCode()).isEqualTo(401);

            // 登录成功 → token
            String token = login(client, base, "admin", "admin123");

            // 带 token 读 → 200
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/health"))
                            .header("Authorization", bearer(token)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);

            // 写操作缺 reason → 400
            HttpResponse<String> noReason = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/kick"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", bearer(token))
                            .POST(HttpRequest.BodyPublishers.ofString("{\"characterId\":1}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(noReason.statusCode()).isEqualTo(400);

            // 带 reason 写 → 执行（404 表示已通过鉴权到达业务）
            HttpResponse<String> withReason = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/kick"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", bearer(token))
                            .header("X-Admin-Reason", "auth-e2e")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"characterId\":1}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(withReason.statusCode()).isEqualTo(404);

            // 创建 auditor 账号（仅 admin:read），登录后访问写端点 → 403
            createAuditor(ctx);
            String auditorToken = login(client, base, "auditor1", "auditor123");

            HttpResponse<String> forbidden = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/kick"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", bearer(auditorToken))
                            .header("X-Admin-Reason", "auditor-e2e")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"characterId\":1}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(forbidden.statusCode()).isEqualTo(403);

            // auditor 读 → 200
            HttpResponse<String> auditorRead = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/admin/v1/health"))
                            .header("Authorization", bearer(auditorToken)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(auditorRead.statusCode()).isEqualTo(200);

            // 审计落库
            AdminOperationAuditRepository audits = ctx.getBean(AdminOperationAuditRepository.class);
            assertThat(audits.count()).isGreaterThan(0);
        }
    }

    private static void createAuditor(ApplicationContext ctx) {
        Account account = new Account();
        account.setName("auditor1");
        account.setPassword(BCrypt.hashpw("auditor123", BCrypt.gensalt()));
        account.setWebAdmin(1);
        ctx.getBean(AccountRepository.class).insert(account);

        AdminRole auditor = ctx.getBean(AdminRoleRepository.class).findByRoleCode("auditor").orElseThrow();
        AccountAdminRole relation = new AccountAdminRole();
        relation.setAccountId(account.getId());
        relation.setRoleId(auditor.getId());
        ctx.getBean(AccountAdminRoleRepository.class).insert(relation);
    }

    private static String login(HttpClient client, String base, String name, String password) throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(base + "/admin/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"name\":\"" + name + "\",\"password\":\"" + password + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        return extractToken(resp.body());
    }

    private static String extractToken(String json) {
        String needle = "\"token\":\"";
        int start = json.indexOf(needle);
        assertThat(start).isGreaterThanOrEqualTo(0);
        int valueStart = start + needle.length();
        int end = json.indexOf('"', valueStart);
        return json.substring(valueStart, end);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
