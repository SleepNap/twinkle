package org.gms.httpapi.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import lombok.extern.log4j.Log4j2;
import org.gms.httpapi.auth.ApiKeyAuthFilter;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.contract.ApiErrorResponses;
import org.gms.httpapi.execution.ToolExecutionService;
import org.gms.httpapi.execution.ToolProtocolException;

import java.util.Map;

/** twish v0.1 统一 Tool 执行入口与同步结果查询。 */
@Controller("/api/v1/tool-executions")
@Produces(MediaType.APPLICATION_JSON)
@Log4j2
public final class ToolExecutionController {

    private final ToolExecutionService executionService;

    public ToolExecutionController(ToolExecutionService executionService) {
        this.executionService = executionService;
    }

    @Post
    public HttpResponse<?> execute(HttpRequest<?> request, @Body Map<String, Object> body) {
        String fallbackRequestId = requestId(request);
        try {
            ToolExecutionService.ExecutionResult result = executionService.execute(
                    principal(request), body, fallbackRequestId);
            request.setAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE, result.requestId());
            return HttpResponse.ok(result.result());
        } catch (ToolProtocolException e) {
            String effectiveRequestId = e.requestId() == null ? fallbackRequestId : e.requestId();
            request.setAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE, effectiveRequestId);
            return ApiErrorResponses.response(e.httpStatus(), effectiveRequestId, e.executionId(),
                    e.code(), e.getMessage(), e.retryable(), e.details());
        } catch (RuntimeException e) {
            log.error("Tool 执行出现未分类异常: requestId={}", fallbackRequestId, e);
            return ApiErrorResponses.response(HttpStatus.INTERNAL_SERVER_ERROR, fallbackRequestId,
                    null, "internal_error", "Tool 执行失败", false, Map.of());
        }
    }

    @Get("/{executionId}")
    public HttpResponse<?> find(HttpRequest<?> request, @PathVariable String executionId) {
        return executionService.find(principal(request), executionId)
                .<HttpResponse<?>>map(HttpResponse::ok)
                .orElseGet(() -> ApiErrorResponses.response(HttpStatus.NOT_FOUND,
                        requestId(request), null, "resource_not_found",
                        "执行记录不存在或不可见", false, Map.of()));
    }

    private static ApiPrincipal principal(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE, ApiPrincipal.class)
                .orElseThrow(() -> new IllegalStateException("API principal missing"));
    }

    private static String requestId(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE, String.class).orElse("unknown");
    }
}
