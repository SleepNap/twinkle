package org.gms.httpapi.api.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.gms.httpapi.auth.ApiKeyService;
import org.gms.httpapi.auth.ApiKeyAuthFilter;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.api.v1.dto.error.SimpleErrorMessageResponse;
import org.gms.httpapi.api.v1.dto.error.SimpleErrorResponse;
import org.gms.httpapi.api.v1.dto.request.IssueApiKeyRequest;
import org.gms.httpapi.api.v1.dto.request.UpdateApiKeyScopesRequest;
import org.gms.httpapi.api.v1.dto.response.ApiKeySummaryResponse;
import org.gms.httpapi.api.v1.dto.response.IssuedApiKeyResponse;
import org.gms.httpapi.api.v1.mapper.PublicApiV1Mapper;

import java.util.List;

/** 由 keys:manage scope 保护的 API-key 生命周期接口。 */
@Controller(ApiRoutes.PUBLIC_V1 + "/auth/keys")
@Produces(MediaType.APPLICATION_JSON)
public final class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Post
    @Operation(summary = "签发 API key", responses = {
            @ApiResponse(responseCode = "201", content = @Content(
                    schema = @Schema(implementation = IssuedApiKeyResponse.class))),
            @ApiResponse(responseCode = "400", content = @Content(
                    schema = @Schema(implementation = SimpleErrorMessageResponse.class)))
    })
    public HttpResponse<?> issue(HttpRequest<?> request, @Body IssueApiKeyRequest body) {
        try {
            return HttpResponse.created(PublicApiV1Mapper.issuedKey(apiKeyService.issue(
                    principal(request), body.displayName(), body.ownerAccountId(), body.scopes(),
                    body.expiresAt())));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(new SimpleErrorMessageResponse(
                    "invalid_key_request", e.getMessage()));
        }
    }

    @Get
    public List<ApiKeySummaryResponse> list(HttpRequest<?> request) {
        return apiKeyService.list(principal(request)).stream()
                .map(PublicApiV1Mapper::keySummary)
                .toList();
    }

    @Delete("/{keyPrefix}")
    @Operation(summary = "吊销 API key", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = @Content(
                    schema = @Schema(implementation = SimpleErrorResponse.class)))
    })
    public HttpResponse<?> revoke(HttpRequest<?> request, @PathVariable String keyPrefix) {
        if (!apiKeyService.revoke(principal(request), keyPrefix)) {
            return HttpResponse.notFound(new SimpleErrorResponse("api_key_not_found"));
        }
        return HttpResponse.noContent();
    }

    @Post("/{keyPrefix}/disable")
    @Operation(summary = "停用 API key", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = @Content(
                    schema = @Schema(implementation = SimpleErrorResponse.class)))
    })
    public HttpResponse<?> disable(HttpRequest<?> request, @PathVariable String keyPrefix) {
        if (!apiKeyService.setDisabled(principal(request), keyPrefix, true)) {
            return HttpResponse.notFound(new SimpleErrorResponse("api_key_not_found"));
        }
        return HttpResponse.noContent();
    }

    @Post("/{keyPrefix}/enable")
    @Operation(summary = "启用 API key", responses = {
            @ApiResponse(responseCode = "204"),
            @ApiResponse(responseCode = "404", content = @Content(
                    schema = @Schema(implementation = SimpleErrorResponse.class)))
    })
    public HttpResponse<?> enable(HttpRequest<?> request, @PathVariable String keyPrefix) {
        if (!apiKeyService.setDisabled(principal(request), keyPrefix, false)) {
            return HttpResponse.notFound(new SimpleErrorResponse("api_key_not_found"));
        }
        return HttpResponse.noContent();
    }

    @Post("/{keyPrefix}/rotate")
    @Operation(summary = "轮换 API key", responses = {
            @ApiResponse(responseCode = "201", content = @Content(
                    schema = @Schema(implementation = IssuedApiKeyResponse.class))),
            @ApiResponse(responseCode = "404", content = @Content(
                    schema = @Schema(implementation = SimpleErrorResponse.class)))
    })
    public HttpResponse<?> rotate(HttpRequest<?> request, @PathVariable String keyPrefix) {
        return apiKeyService.rotate(principal(request), keyPrefix)
                .<HttpResponse<?>>map(key -> HttpResponse.created(PublicApiV1Mapper.issuedKey(key)))
                .orElseGet(() -> HttpResponse.notFound(
                        new SimpleErrorResponse("api_key_not_found")));
    }

    @Put("/{keyPrefix}/scopes")
    @Operation(summary = "更新 API key scopes", responses = {
            @ApiResponse(responseCode = "200", content = @Content(
                    schema = @Schema(implementation = ApiKeySummaryResponse.class))),
            @ApiResponse(responseCode = "400", content = @Content(
                    schema = @Schema(implementation = SimpleErrorMessageResponse.class))),
            @ApiResponse(responseCode = "404", content = @Content(
                    schema = @Schema(implementation = SimpleErrorResponse.class)))
    })
    public HttpResponse<?> scopes(HttpRequest<?> request, @PathVariable String keyPrefix,
                                  @Body UpdateApiKeyScopesRequest body) {
        try {
            return apiKeyService.updateScopes(principal(request), keyPrefix, body.scopes())
                    .<HttpResponse<?>>map(key -> HttpResponse.ok(
                            PublicApiV1Mapper.keySummary(key)))
                    .orElseGet(() -> HttpResponse.notFound(
                            new SimpleErrorResponse("api_key_not_found")));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(new SimpleErrorMessageResponse(
                    "invalid_key_request", e.getMessage()));
        }
    }

    private static ApiPrincipal principal(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE, ApiPrincipal.class)
                .orElseThrow(() -> new IllegalStateException("API principal missing"));
    }
}
