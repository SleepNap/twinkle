package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import org.gms.config.ConfigFacade;
import org.gms.httpapi.billing.AiPolicyService;
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
 * AI 权限与预算策略的入口覆盖回归。
 *
 * <p>与 {@code AiBillingE2ETest} 分工：那个锁"额度前置存在"，本测试锁"策略真的拦得住"，
 * 且两条入口（{@code /api/v1/ai/chat} 与能力面 tool-executions）行为一致。
 *
 * <p>另锁住一条安全语义：<b>管理员凭据免计费，但不免全局开关</b>。管理员 key 泄漏时
 * 若能绕过总开关，就等于无限免费调用外部模型。
 *
 * <p>注意：同一 JVM 内 MybatisFlexBootstrap 注册的是全局默认 DataSource，先启动的
 * ApplicationContext 会被后续测试类共享，故此处一律不做表的绝对计数断言。
 */
class AiPolicyE2ETest {

    private static final String BOOTSTRAP_KEY = "twinkle-e2e-policy-key-0123456789";

    // 每个用例用独立账号：同一 JVM 内 MybatisFlexBootstrap 注册的是全局默认 DataSource，
    // 先启动的 context 会被后续用例共享，各用例即使指向不同临时库文件也读写同一个库。
    // 复用同一个 accountId 会让先跑的用例写下的策略污染后跑用例的断言。
    private static final long DISABLED_ACCOUNT_ID = 90101L;
    private static final long MODEL_BLOCKED_ACCOUNT_ID = 90102L;
    private static final long DAILY_LIMIT_ACCOUNT_ID = 90103L;
    private static final long GLOBAL_SWITCH_ACCOUNT_ID = 90104L;

    @Test
    void 账号策略禁用时两条入口都被拒() throws Exception {
        withServer("policy-disabled", (ctx, base, client) -> {
            ctx.getBean(BillingService.class).adjust(DISABLED_ACCOUNT_ID, 1000L, "admin_adjust");
            String token = issueKeyBoundToAccount(client, base, DISABLED_ACCOUNT_ID);
            ctx.getBean(AiPolicyService.class).upsert(DISABLED_ACCOUNT_ID, 0, "",
                    0L, 0L, 0L, "e2e");

            HttpResponse<String> chat = chat(client, base, token);
            // 策略拒绝不可重试，用 403 而非限流的 429。
            assertThat(chat.statusCode()).isEqualTo(403);
            assertThat(chat.body()).contains("policy_disabled");

            HttpResponse<String> execution = toolExecution(client, base, token, "req_policy_disabled");
            assertThat(execution.statusCode()).isEqualTo(403);
            assertThat(execution.body()).contains("policy_disabled");
        });
    }

    @Test
    void 模型不在账号白名单时两条入口都被拒() throws Exception {
        withServer("model-blocked", (ctx, base, client) -> {
            ctx.getBean(BillingService.class).adjust(MODEL_BLOCKED_ACCOUNT_ID, 1000L, "admin_adjust");
            String token = issueKeyBoundToAccount(client, base, MODEL_BLOCKED_ACCOUNT_ID);
            ctx.getBean(AiPolicyService.class).upsert(MODEL_BLOCKED_ACCOUNT_ID, 1,
                    "openai/gpt-4o", 0L, 0L, 0L, "e2e");

            HttpResponse<String> chat = chat(client, base, token);
            assertThat(chat.statusCode()).isEqualTo(403);
            assertThat(chat.body()).contains("model_not_allowed");

            HttpResponse<String> execution = toolExecution(client, base, token, "req_model_blocked");
            assertThat(execution.statusCode()).isEqualTo(403);
            assertThat(execution.body()).contains("model_not_allowed");
        });
    }

    @Test
    void 日调用上限达到后被拒解除后放行() throws Exception {
        withServer("daily-limit", (ctx, base, client) -> {
            ctx.getBean(BillingService.class).adjust(DAILY_LIMIT_ACCOUNT_ID, 1000L, "admin_adjust");
            String token = issueKeyBoundToAccount(client, base, DAILY_LIMIT_ACCOUNT_ID);
            AiPolicyService policies = ctx.getBean(AiPolicyService.class);
            // 日上限 1 次：第一次放行并计数，第二次即超限。
            policies.upsert(DAILY_LIMIT_ACCOUNT_ID, 1, "", 0L, 1L, 0L, "e2e");

            assertThat(chat(client, base, token).statusCode()).isEqualTo(200);

            HttpResponse<String> second = chat(client, base, token);
            // 额度类拒绝可重试，维持 429。
            assertThat(second.statusCode()).isEqualTo(429);
            assertThat(second.body()).contains("daily_limit_exceeded");

            // 放开上限后立即恢复：治理层每次实时查库，无需刷新凭据。
            policies.upsert(DAILY_LIMIT_ACCOUNT_ID, 1, "", 0L, 0L, 0L, "e2e");
            assertThat(chat(client, base, token).statusCode()).isEqualTo(200);
        });
    }

    @Test
    void 全局开关关闭时连管理员凭据也被拒() throws Exception {
        withServer("global-off", (ctx, base, client) -> {
            ctx.getBean(BillingService.class).adjust(GLOBAL_SWITCH_ACCOUNT_ID, 1000L, "admin_adjust");
            String token = issueKeyBoundToAccount(client, base, GLOBAL_SWITCH_ACCOUNT_ID);
            assertThat(chat(client, base, token).statusCode()).isEqualTo(200);
            assertThat(adminChat(client, base).statusCode()).isEqualTo(200);

            ConfigFacade config = ctx.getBean(ConfigFacade.class);
            try {
                setRuntimeEnabled(client, base, false);

                HttpResponse<String> blocked = chat(client, base, token);
                // 整体关闭属于服务不可用，不是调用方的错。
                assertThat(blocked.statusCode()).isEqualTo(503);
                assertThat(blocked.body()).contains("ai_disabled");

                // 关键断言：管理员凭据免计费，但不免全局开关。
                HttpResponse<String> adminBlocked = adminChat(client, base);
                assertThat(adminBlocked.statusCode()).isEqualTo(503);
                assertThat(adminBlocked.body()).contains("ai_disabled");
            } finally {
                // 配置也落在共享库里：断言失败也必须恢复，否则同类其它用例全被 503 带崩。
                setRuntimeEnabled(client, base, true);
            }
            assertThat(config.getOrDefault("ai.runtime.enabled", false)).isTrue();
            assertThat(chat(client, base, token).statusCode()).isEqualTo(200);
        });
    }

    private static void setRuntimeEnabled(HttpClient client, String base, boolean enabled) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + "/internal/v1/config"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"key\":\"ai.runtime.enabled\",\"value\":\"" + enabled + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("配置写入").isEqualTo(200);
    }

    private static HttpResponse<String> chat(HttpClient client, String base, String token) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/ai/chat"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"在线统计\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> toolExecution(HttpClient client, String base, String token,
                                                      String requestId) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/tool-executions"))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(toolBody(requestId)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 绑定计费账号的普通凭据（非 * 管理员凭据，受账号策略约束）。 */
    private static String issueKeyBoundToAccount(HttpClient client, String base,
                                                 long accountId) throws Exception {
        return issueKey(client, base,
                "{\"displayName\":\"ai-policy-e2e-" + accountId + "\",\"ownerAccountId\":" + accountId
                        + ",\"scopes\":[\"ai:use\"]}");
    }

    /**
     * 用管理员凭据发起对话。
     *
     * <p>管理员凭据就是 bootstrap key 本身——{@code *} 不在 {@code ApiScopes.SUPPORTED} 里，
     * 签发接口签不出这种 key。它的 principal scope 为 {@code *}，反查不到 API-key 记录，
     * 因而免计费；本测试要证明它<b>不免全局开关</b>。
     */
    private static HttpResponse<String> adminChat(HttpClient client, String base) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/ai/chat"))
                        .header("X-API-Key", BOOTSTRAP_KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"在线统计\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String issueKey(HttpClient client, String base, String body) throws Exception {
        HttpResponse<String> issued = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/keys"))
                        .header("X-API-Key", BOOTSTRAP_KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(issued.statusCode()).isEqualTo(201);
        String token = issued.body().replaceFirst(".*\"token\":\"([^\"]+)\".*", "$1");
        assertThat(token).as("签发返回的 token").isNotEqualTo(issued.body());
        return token;
    }

    private static String toolBody(String requestId) {
        return "{\"contractVersion\":\"0.1\",\"requestId\":\"" + requestId
                + "\",\"taskId\":\"task_ai_policy\",\"stepId\":\"step_ai_policy\","
                + "\"toolId\":\"server.agent.investigate\",\"toolVersion\":\"1.0.0\","
                + "\"input\":{\"conversationId\":\"ai-policy-e2e\",\"message\":\"在线统计\"},"
                + "\"dryRun\":false,\"idempotencyKey\":null,\"approvalToken\":null,"
                + "\"clientContext\":{\"locale\":\"zh-CN\",\"source\":\"desktop\","
                + "\"intentSummary\":\"验证策略入口覆盖\"}}";
    }

    private static void withServer(String name, ServerCase body) throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-aipolicy-" + name).resolve("test.db").toString();
        String scriptDir = Files.createTempDirectory("twinkle-aipolicy-script-" + name).toString();
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
            body.run(ctx, "http://127.0.0.1:" + server.getPort(), HttpClient.newHttpClient());
        }
    }

    @FunctionalInterface
    private interface ServerCase {
        public void run(ApplicationContext ctx, String base, HttpClient client) throws Exception;
    }
}
