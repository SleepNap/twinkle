package org.gms.httpapi.api.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

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
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.extern.log4j.Log4j2;
import org.gms.httpapi.auth.ApiKeyAuthFilter;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.api.v1.contract.ApiContract;
import org.gms.httpapi.api.v1.contract.ApiErrorResponses;
import org.gms.httpapi.api.v1.dto.request.ToolExecutionRequest;
import org.gms.httpapi.api.v1.dto.error.ErrorEnvelope;
import org.gms.httpapi.api.v1.dto.response.ToolExecutionResponse;
import org.gms.httpapi.api.v1.mapper.ToolExecutionV1Mapper;
import org.gms.httpapi.execution.ToolExecutionService;
import org.gms.httpapi.execution.ToolProtocolException;
import org.gms.i18n.I18nService;

import java.util.Map;

/** 公共 API v1 的统一 Tool 执行入口与同步结果查询。 */
@Controller(ApiRoutes.PUBLIC_V1 + "/tool-executions")
@Produces(MediaType.APPLICATION_JSON)
@Log4j2
@ExecuteOn(TaskExecutors.BLOCKING)
public final class ToolExecutionController {

    private final ToolExecutionService executionService;
    private final I18nService i18n;

    public ToolExecutionController(ToolExecutionService executionService, I18nService i18n) {
        this.executionService = executionService;
        this.i18n = i18n;
    }

    @Post
    @Operation(summary = "执行 Tool", responses = {
            @ApiResponse(responseCode = "200", content = @Content(
                    schema = @Schema(implementation = ToolExecutionResponse.class))),
            @ApiResponse(responseCode = "400", content = @Content(
                    schema = @Schema(implementation = ErrorEnvelope.class))),
            @ApiResponse(responseCode = "500", content = @Content(
                    schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    public HttpResponse<?> execute(HttpRequest<?> request, @Body ToolExecutionRequest body) {
        String fallbackRequestId = requestId(request);
        try {
            ToolExecutionService.ExecutionResult result = executionService.execute(
                    principal(request), ToolExecutionV1Mapper.toServiceInput(body),
                    fallbackRequestId, ApiContract.VERSION);
            request.setAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE, result.requestId());
            return HttpResponse.ok(ToolExecutionV1Mapper.response(result.result()));
        } catch (ToolProtocolException e) {
            String effectiveRequestId = e.requestId() == null ? fallbackRequestId : e.requestId();
            request.setAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE, effectiveRequestId);
            return ApiErrorResponses.response(e.httpStatus(), effectiveRequestId, e.executionId(),
                    e.code(), e.getMessage(), e.retryable(), e.details());
        } catch (RuntimeException e) {
            log.error(i18n.message("log.tool.unclassified_error"), fallbackRequestId, e);
            return ApiErrorResponses.response(HttpStatus.INTERNAL_SERVER_ERROR, fallbackRequestId,
                    null, "internal_error", i18n.message("api.error.tool_execution_failed"), false, Map.of());
        }
    }

    @Get("/{executionId}")
    @Operation(summary = "查询 Tool 执行结果", responses = {
            @ApiResponse(responseCode = "200", content = @Content(
                    schema = @Schema(implementation = ToolExecutionResponse.class))),
            @ApiResponse(responseCode = "404", content = @Content(
                    schema = @Schema(implementation = ErrorEnvelope.class)))
    })
    public HttpResponse<?> find(HttpRequest<?> request, @PathVariable String executionId) {
        return executionService.find(principal(request), executionId)
                .<HttpResponse<?>>map(result -> HttpResponse.ok(
                        ToolExecutionV1Mapper.response(result)))
                .orElseGet(() -> ApiErrorResponses.response(HttpStatus.NOT_FOUND,
                        requestId(request), null, "resource_not_found",
                        i18n.message("api.error.execution_not_found"), false, Map.of()));
    }

    private static ApiPrincipal principal(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE, ApiPrincipal.class)
                .orElseThrow(() -> new IllegalStateException("API principal missing"));
    }

    private static String requestId(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE, String.class).orElse("unknown");
    }
}
