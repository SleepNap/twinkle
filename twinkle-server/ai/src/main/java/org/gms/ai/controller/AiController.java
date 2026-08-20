package org.gms.ai.controller;

import io.micronaut.http.MediaType;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.gms.ai.service.AiFacade;
import org.gms.service.agent.AiGovernanceException;

import java.util.Map;
import java.util.UUID;

/**
 * AI 客户端接口（架构 M3-2：客户端流式接口 + 报表）。
 *
 * <p>挂在第三方 API 面（/api/v1/ai/*），限流由 http-api 的 ApiRateLimitFilter
 * （@Filter("/api/v1/**")）统一拦截。数据经 {@link AiFacade}（Agent + 工具，不直踩游戏内存）。
 */
@Controller("/api/v1/ai")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class AiController {

    private final AiFacade aiFacade;

    public AiController(AiFacade aiFacade) {
        this.aiFacade = aiFacade;
    }

    /** 对话（阻塞，返回最终文本；工具自动循环）。计费由 AiFacade 内的唯一计费点执行。 */
    @Post("/chat")
    public HttpResponse<?> chat(HttpRequest<?> request, @Body AgentChatRequest body) {
        String conversationId = body.conversationId() == null || body.conversationId().isBlank()
                ? "conv-" + UUID.randomUUID() : body.conversationId();
        try {
            return HttpResponse.ok(aiFacade.investigate(conversationId, body.message(),
                    attribute(request, "twinkle.api.request-id", UUID.randomUUID().toString()),
                    attribute(request, "twinkle.api.subject-id", "api-agent"),
                    attribute(request, "twinkle.api.credential-id", "api-key"),
                    "api"));
        } catch (AiGovernanceException e) {
            // 与能力面 tool-executions 的治理拒绝保持同一响应形态与状态码分流。
            return HttpResponse.status(governanceStatus(e))
                    .body(Map.of("error", e.code(), "message", e.getMessage()));
        }
    }

    /** 主动结束会话并释放上下文。 */
    @Delete("/chat/{conversationId}")
    public Map<String, Object> closeConversation(HttpRequest<?> request,
                                                 @PathVariable String conversationId) {
        return Map.of("conversationId", conversationId,
                "evicted", aiFacade.closeConversation(conversationId,
                        attribute(request, "twinkle.api.subject-id", "api-agent")));
    }

    /** 在线统计报表（结构化输出）。 */
    @Get("/report/online")
    public Map<String, Object> onlineReport() {
        org.gms.ai.service.OnlineReport report = aiFacade.onlineReport();
        return Map.of("onlineCount", report.getOnlineCount(), "summary", report.getSummary());
    }

    /** 调用统计（观测）。 */
    @Get("/usage")
    public Map<String, Object> usage() {
        return Map.of("callCount", aiFacade.callCount(),
                "model", aiFacade.modelDescriptor(),
                "externalModel", aiFacade.externalModel());
    }

    private static String attribute(HttpRequest<?> request, String name, String fallback) {
        return request.getAttribute(name, String.class).orElse(fallback);
    }

    /** 策略类拒绝重试也不会通过，用 403；整体关闭是服务不可用，用 503；额度类维持 429。 */
    private static HttpStatus governanceStatus(AiGovernanceException e) {
        return switch (e.kind()) {
            case POLICY -> HttpStatus.FORBIDDEN;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case QUOTA -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }

    /** Agent 对话请求；conversationId 省略时由服务端创建。 */
    public record AgentChatRequest(String conversationId, String message) {
    }
}
