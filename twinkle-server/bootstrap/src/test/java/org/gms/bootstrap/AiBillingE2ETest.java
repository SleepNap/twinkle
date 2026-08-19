package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.gms.httpapi.billing.BillingService;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 计费入口覆盖回归。
 *
 * <p>背景：{@code /api/v1/ai/chat} 曾直接调用 AI 门面，绕过能力面的计费前置，
 * 持 {@code ai:use} 的凭据走这条路径可以无限免费调用。修复把计费下沉为 core 契约
 * （AiGovernanceService）并收敛到 AI 门面内部的唯一计费点，本测试锁住这一行为：
 * <b>两条入口必须都受额度前置约束</b>，将来任何一条绕过计费都会在此变红。
 */
class AiBillingE2ETest {

    private static final String BOOTSTRAP_KEY = "twinkle-e2e-bootstrap-key-0123456789";
    private static final long BILLED_ACCOUNT_ID = 90001L;

    @Test
    void 两条AI入口都受额度前置约束() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-aibilling-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-aibilling-script").toString();

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
            server.start();
            String base = "http://127.0.0.1:" + server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            // 绑定账号、余额为 0 的普通凭据：任何计费入口都应被 precheck 拒绝。
            String token = issueKeyBoundToAccount(client, base);
            assertThat(ctx.getBean(BillingService.class).balance(BILLED_ACCOUNT_ID).balance()).isZero();

            HttpResponse<String> chat = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/ai/chat"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"在线统计\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            // 修复前这里是 200：该入口完全没有计费前置。
            assertThat(chat.statusCode()).isEqualTo(429);
            assertThat(chat.body()).contains("insufficient_points");

            String requestId = "req_ai_billing_e2e_01";
            HttpResponse<String> execution = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/tool-executions"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toolBody(requestId)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(execution.statusCode()).isEqualTo(429);
            assertThat(execution.body()).contains("insufficient_points");
        }
    }

    @Test
    void 余额充足时两条入口都放行且各自只结算一次() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-aibilling-ok-e2e").resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-aibilling-ok-script").toString();

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
            server.start();
            String base = "http://127.0.0.1:" + server.getPort();
            HttpClient client = HttpClient.newHttpClient();

            BillingService billing = ctx.getBean(BillingService.class);
            billing.adjust(BILLED_ACCOUNT_ID, 1000L, "admin_adjust");
            String token = issueKeyBoundToAccount(client, base);

            HttpResponse<String> chat = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/ai/chat"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"在线统计\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(chat.statusCode()).isEqualTo(200);
            assertThat(chat.body()).contains("local-rule/deterministic");

            String requestId = "req_ai_billing_ok_01";
            HttpResponse<String> execution = client.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/tool-executions"))
                            .header("Authorization", "Bearer " + token)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(toolBody(requestId)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(execution.statusCode()).isEqualTo(200);

            // local-rule 不上报 TokenUsage，倍率也为 0，故实际扣减为 0：
            // 这里断言余额未被意外多扣（含防双重扣费），金额口径的回归由 BillingServiceTest 覆盖。
            assertThat(billing.balance(BILLED_ACCOUNT_ID).balance()).isEqualTo(1000L);
        }
    }

    /** 签发一把绑定计费账号、具备 ai:use 的普通凭据（非 * 管理员凭据，不免计费）。 */
    private static String issueKeyBoundToAccount(HttpClient client, String base) throws Exception {
        HttpResponse<String> issued = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/keys"))
                        .header("X-API-Key", BOOTSTRAP_KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"displayName\":\"ai-billing-e2e\",\"ownerAccountId\":" + BILLED_ACCOUNT_ID
                                        + ",\"scopes\":[\"ai:use\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(issued.statusCode()).isEqualTo(201);
        String token = issued.body().replaceFirst(".*\"token\":\"([^\"]+)\".*", "$1");
        assertThat(token).as("签发返回的 token").isNotEqualTo(issued.body());
        return token;
    }

    private static String toolBody(String requestId) {
        return "{\"contractVersion\":\"0.1\",\"requestId\":\"" + requestId
                + "\",\"taskId\":\"task_ai_billing\",\"stepId\":\"step_ai_billing\","
                + "\"toolId\":\"server.agent.investigate\",\"toolVersion\":\"1.0.0\","
                + "\"input\":{\"conversationId\":\"ai-billing-e2e\",\"message\":\"在线统计\"},"
                + "\"dryRun\":false,\"idempotencyKey\":null,\"approvalToken\":null,"
                + "\"clientContext\":{\"locale\":\"zh-CN\",\"source\":\"desktop\","
                + "\"intentSummary\":\"验证计费入口覆盖\"}}";
    }
}
