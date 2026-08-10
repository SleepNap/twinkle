package org.gms.httpapi.controller;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import org.gms.httpapi.auth.ApiKeyAuthFilter;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.capability.ToolCatalogService;
import org.gms.httpapi.contract.ApiErrorResponses;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** twish 的轻量 Tool 目录、按需详情和公开 OpenAPI 契约。 */
@Controller("/api/v1")
public final class CapabilityController {

    private final ToolCatalogService toolCatalogService;

    public CapabilityController(ToolCatalogService toolCatalogService) {
        this.toolCatalogService = toolCatalogService;
    }

    @Get("/capabilities{?profile,query}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> capabilities(HttpRequest<?> request,
                                        @QueryValue(defaultValue = "") String profile,
                                        @QueryValue(defaultValue = "") String query) {
        ApiPrincipal principal = principal(request);
        String etag = toolCatalogService.etag(principal);
        if (request.getHeaders().findFirst(HttpHeaders.IF_NONE_MATCH).filter(etag::equals).isPresent()) {
            return HttpResponse.notModified().header(HttpHeaders.ETAG, etag);
        }
        try {
            return HttpResponse.ok(toolCatalogService.catalog(principal, profile, query))
                    .header(HttpHeaders.ETAG, etag);
        } catch (IllegalArgumentException e) {
            return ApiErrorResponses.response(io.micronaut.http.HttpStatus.BAD_REQUEST,
                    requestId(request), null, "invalid_input", e.getMessage(), false, Map.of());
        }
    }

    @Get("/capabilities/{toolId}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> capability(HttpRequest<?> request, @PathVariable String toolId) {
        return toolCatalogService.detail(principal(request), toolId)
                .<HttpResponse<?>>map(HttpResponse::ok)
                .orElseGet(() -> ApiErrorResponses.response(io.micronaut.http.HttpStatus.NOT_FOUND,
                        requestId(request), null, "resource_not_found",
                        "Tool 不存在或对当前凭据不可见", false, Map.of()));
    }

    @Get("/openapi.yaml")
    @Produces("application/yaml")
    public String openApi() {
        try (InputStream input = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("openapi/twinkle-capability-v1.yaml")) {
            if (input == null) {
                throw new IllegalStateException("OpenAPI contract missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取 OpenAPI 契约失败", e);
        }
    }

    private static ApiPrincipal principal(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE, ApiPrincipal.class)
                .orElseThrow(() -> new IllegalStateException("API principal missing"));
    }

    private static String requestId(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE, String.class).orElse("unknown");
    }
}
