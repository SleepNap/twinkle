package org.gms.httpapi.limit;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.filter.ServerFilterPhase;
import org.gms.httpapi.auth.ApiKeyAuthFilter;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.contract.ApiErrorResponses;
import org.gms.httpapi.limit.ApiRateLimiter;
import org.reactivestreams.Publisher;

/**
 * 第三方 API 限流过滤器（架构 M3-1：/api/v1 限流 + 版本化）。
 *
 * <p>只拦截 {@code /api/v1/**}（第三方对外面）；{@code /internal/v1/**}（官网转调）
 * 不限流。限流键取来源 IP（管理侧对外 API 按客户端维度限流）。被限流返回 429。
 *
 * <p>HTTP 请求在 Micronaut 的 Netty EventLoop 处理（与游戏 Netty 隔离，红线 4）。
 */
@Filter("/api/v1/**")
public final class ApiRateLimitFilter implements HttpServerFilter, Ordered {

    private final ApiRateLimiter rateLimiter;

    public ApiRateLimitFilter(ApiRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.after();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String key = clientKey(request);
        if (!rateLimiter.tryConsume(key)) {
            String requestId = request.getAttribute(ApiKeyAuthFilter.REQUEST_ID_ATTRIBUTE, String.class)
                    .orElse("unknown");
            return io.micronaut.core.async.publisher.Publishers.just(
                    ApiErrorResponses.response(HttpStatus.TOO_MANY_REQUESTS, requestId, null,
                            "rate_limited", "请求频率过高", true, java.util.Map.of()));
        }
        return chain.proceed(request);
    }

    /** 限流键：来源 IP（取 X-Forwarded-For 最后跳或直连地址）。 */
    private String clientKey(HttpRequest<?> request) {
        java.util.Optional<ApiPrincipal> principal = request.getAttribute(
                ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE, ApiPrincipal.class);
        if (principal.isPresent()) {
            return "key:" + principal.get().keyPrefix();
        }
        java.util.Optional<String> fwd = request.getHeaders().findFirst("X-Forwarded-For");
        if (fwd.isPresent() && !fwd.get().isBlank()) {
            // 取链上最后一个（最近一跳）客户端 IP
            String[] parts = fwd.get().split(",");
            return parts[parts.length - 1].trim();
        }
        java.net.InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
