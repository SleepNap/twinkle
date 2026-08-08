package org.gms.ai.controller;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import org.gms.ai.service.AiFacade;

import java.util.Map;

/**
 * AI 客户端接口（架构 M3-2：客户端流式接口 + 报表）。
 *
 * <p>挂在第三方 API 面（/api/v1/ai/*），限流由 http-api 的 ApiRateLimitFilter
 * （@Filter("/api/v1/**")）统一拦截。数据经 {@link AiFacade}（Agent + 工具，不直踩游戏内存）。
 */
@Controller("/api/v1/ai")
@Produces(MediaType.APPLICATION_JSON)
public final class AiController {

    private final AiFacade aiFacade;

    public AiController(AiFacade aiFacade) {
        this.aiFacade = aiFacade;
    }

    /** 对话（阻塞，返回最终文本；工具自动循环）。 */
    @Post("/chat")
    public Map<String, Object> chat(@Body Map<String, String> body) {
        String reply = aiFacade.chat(body.getOrDefault("message", ""));
        return Map.of("reply", reply);
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
        return Map.of("callCount", aiFacade.callCount());
    }
}
