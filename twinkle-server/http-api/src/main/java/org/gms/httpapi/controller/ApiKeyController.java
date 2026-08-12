package org.gms.httpapi.controller;

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
import org.gms.httpapi.auth.ApiKeyService;
import org.gms.httpapi.auth.ApiKeyAuthFilter;
import org.gms.httpapi.auth.ApiPrincipal;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 由 keys:manage scope 保护的 API-key 生命周期接口。 */
@Controller("/api/v1/auth/keys")
@Produces(MediaType.APPLICATION_JSON)
public final class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Post
    public HttpResponse<?> issue(HttpRequest<?> request, @Body IssueKeyRequest body) {
        try {
            return HttpResponse.created(apiKeyService.issue(principal(request), body.displayName(),
                    body.ownerAccountId(), body.scopes(), body.expiresAt()));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", "invalid_key_request", "message", e.getMessage()));
        }
    }

    @Get
    public List<ApiKeyService.KeySummary> list(HttpRequest<?> request) {
        return apiKeyService.list(principal(request));
    }

    @Delete("/{keyPrefix}")
    public HttpResponse<?> revoke(HttpRequest<?> request, @PathVariable String keyPrefix) {
        if (!apiKeyService.revoke(principal(request), keyPrefix)) {
            return HttpResponse.notFound(Map.of("error", "api_key_not_found"));
        }
        return HttpResponse.noContent();
    }

    @Post("/{keyPrefix}/disable")
    public HttpResponse<?> disable(HttpRequest<?> request, @PathVariable String keyPrefix) {
        if (!apiKeyService.setDisabled(principal(request), keyPrefix, true)) {
            return HttpResponse.notFound(Map.of("error", "api_key_not_found"));
        }
        return HttpResponse.noContent();
    }

    @Post("/{keyPrefix}/enable")
    public HttpResponse<?> enable(HttpRequest<?> request, @PathVariable String keyPrefix) {
        if (!apiKeyService.setDisabled(principal(request), keyPrefix, false)) {
            return HttpResponse.notFound(Map.of("error", "api_key_not_found"));
        }
        return HttpResponse.noContent();
    }

    @Post("/{keyPrefix}/rotate")
    public HttpResponse<?> rotate(HttpRequest<?> request, @PathVariable String keyPrefix) {
        return apiKeyService.rotate(principal(request), keyPrefix)
                .<HttpResponse<?>>map(HttpResponse::created)
                .orElseGet(() -> HttpResponse.notFound(Map.of("error", "api_key_not_found")));
    }

    @Put("/{keyPrefix}/scopes")
    public HttpResponse<?> scopes(HttpRequest<?> request, @PathVariable String keyPrefix,
                                  @Body UpdateScopesRequest body) {
        try {
            return apiKeyService.updateScopes(principal(request), keyPrefix, body.scopes())
                    .<HttpResponse<?>>map(HttpResponse::ok)
                    .orElseGet(() -> HttpResponse.notFound(Map.of("error", "api_key_not_found")));
        } catch (IllegalArgumentException e) {
            return HttpResponse.badRequest(Map.of("error", "invalid_key_request", "message", e.getMessage()));
        }
    }

    private static ApiPrincipal principal(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE, ApiPrincipal.class)
                .orElseThrow(() -> new IllegalStateException("API principal missing"));
    }

    public record IssueKeyRequest(String displayName, Long ownerAccountId, Set<String> scopes,
                                  String expiresAt) {
    }

    public record UpdateScopesRequest(Set<String> scopes) {
    }
}
