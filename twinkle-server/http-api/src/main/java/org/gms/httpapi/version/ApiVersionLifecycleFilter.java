package org.gms.httpapi.version;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import org.reactivestreams.Publisher;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** 在认证前拒绝未发布/已退役版本，并为废弃版本补充标准生命周期提示。 */
@Filter({ApiRoutes.PUBLIC_ROOT + "/**", ApiRoutes.ADMIN_ROOT + "/**", ApiRoutes.INTERNAL_ROOT + "/**"})
public final class ApiVersionLifecycleFilter implements HttpServerFilter, Ordered {

    private static final String DEPRECATION_HEADER = "Deprecation";
    private static final String SUNSET_HEADER = "Sunset";

    private final ApiVersionCatalog catalog;

    public ApiVersionLifecycleFilter(ApiVersionCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        return catalog.resolve(request.getPath())
                .<Publisher<MutableHttpResponse<?>>>map(version -> filterVersion(version, request, chain))
                .orElseGet(() -> chain.proceed(request));
    }

    private Publisher<MutableHttpResponse<?>> filterVersion(
            ApiVersionCatalog.ResolvedVersion resolved,
            HttpRequest<?> request,
            ServerFilterChain chain) {
        if (!resolved.registered()) {
            return Publishers.just(HttpResponse.notFound(Map.of(
                    "error", "api_version_not_found",
                    "major", resolved.major(),
                    "plane", resolved.plane().name().toLowerCase())));
        }
        ApiVersionDefinition definition = resolved.definition();
        if (definition.status() == ApiVersionStatus.RETIRED) {
            return Publishers.just(HttpResponse.status(HttpStatus.GONE).body(Map.of(
                    "error", "api_version_retired",
                    "major", resolved.major(),
                    "migrationGuide", definition.migrationGuide())));
        }
        Publisher<MutableHttpResponse<?>> response = chain.proceed(request);
        if (definition.status() != ApiVersionStatus.DEPRECATED) {
            return response;
        }
        return Publishers.map(response, result -> {
            result.header(DEPRECATION_HEADER, "true");
            if (definition.sunsetAt() != null) {
                result.header(SUNSET_HEADER, DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        definition.sunsetAt().atZone(ZoneOffset.UTC)));
            }
            if (!definition.migrationGuide().isBlank()) {
                result.header("Link", "<" + definition.migrationGuide() + ">; rel=\"deprecation\"");
            }
            return result;
        });
    }
}
