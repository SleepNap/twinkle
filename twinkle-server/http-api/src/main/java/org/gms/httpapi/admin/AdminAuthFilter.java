package org.gms.httpapi.admin;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import org.gms.i18n.I18nService;
import org.reactivestreams.Publisher;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 所有 {@code /admin/vN} 管理面共用的强鉴权过滤器：管理员会话认证 → RBAC 授权 → 写操作 reason 校验 → 审计。
 */
@Filter(ApiRoutes.ADMIN_ROOT + "/**")
public final class AdminAuthFilter implements HttpServerFilter, Ordered {

    public static final String PRINCIPAL_ATTRIBUTE = "twinkle.admin.principal";
    public static final String REQUEST_ID_ATTRIBUTE = "twinkle.admin.request-id";
    public static final String REASON_HEADER = "X-Admin-Reason";
    public static final String BEFORE_SUMMARY_ATTRIBUTE = "twinkle.admin.before-summary";
    public static final String AFTER_SUMMARY_ATTRIBUTE = "twinkle.admin.after-summary";

    private static final int MAX_REASON_LENGTH = 256;

    private final AdminSessionService sessionService;
    private final AdminAuditService auditService;
    private final AdminAccessPolicy accessPolicy;
    private final I18nService i18n;

    public AdminAuthFilter(AdminSessionService sessionService, AdminAuditService auditService,
                           AdminAccessPolicy accessPolicy, I18nService i18n) {
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.accessPolicy = accessPolicy;
        this.i18n = i18n;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.before();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        AdminAccessPolicy.Policy policy = accessPolicy.resolve(request.getMethod(), request.getPath());
        if (policy.publicEndpoint()) {
            return chain.proceed(request);
        }

        long started = System.nanoTime();
        String requestId = requestId(request);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);

        Optional<AdminPrincipal> authenticated = sessionService.authenticate(extractToken(request));
        if (authenticated.isEmpty()) {
            auditService.record(requestId, null, request.getMethodName(), request.getPath(),
                    operationOf(request.getPath()), "", "", "",
                    "unauthenticated", HttpStatus.UNAUTHORIZED.getCode(),
                    remoteAddress(request), elapsedMs(started));
            return Publishers.just(errorResponse(HttpStatus.UNAUTHORIZED, requestId,
                    "admin_unauthenticated", message("admin.error.unauthenticated")));
        }

        AdminPrincipal principal = authenticated.get();
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);

        if (!principal.permits(policy.requiredPermission())) {
            auditService.record(requestId, principal, request.getMethodName(), request.getPath(),
                    operationOf(request.getPath()), reason(request), "", "",
                    "forbidden", HttpStatus.FORBIDDEN.getCode(),
                    remoteAddress(request), elapsedMs(started));
            return Publishers.just(errorResponse(HttpStatus.FORBIDDEN, requestId,
                    "admin_permission_denied", message("admin.error.permission_denied")));
        }

        String reason = reason(request);
        if (isWrite(request.getMethod()) && reason.isBlank()) {
            auditService.record(requestId, principal, request.getMethodName(), request.getPath(),
                    operationOf(request.getPath()), "", "", "",
                    "reason_required", HttpStatus.BAD_REQUEST.getCode(),
                    remoteAddress(request), elapsedMs(started));
            return Publishers.just(errorResponse(HttpStatus.BAD_REQUEST, requestId,
                    "admin_reason_required", message("admin.error.reason_required")));
        }

        Publisher<MutableHttpResponse<?>> response = chain.proceed(request);
        return Publishers.map(response, result -> {
            String effectiveRequestId = request.getAttribute(REQUEST_ID_ATTRIBUTE, String.class)
                    .orElse(requestId);
            result.header("X-Request-Id", effectiveRequestId);
            auditService.record(effectiveRequestId, principal, request.getMethodName(),
                    request.getPath(), operationOf(request.getPath()), reason,
                    request.getAttribute(BEFORE_SUMMARY_ATTRIBUTE, String.class).orElse(""),
                    request.getAttribute(AFTER_SUMMARY_ATTRIBUTE, String.class).orElse(""),
                    "allowed", result.getStatus().getCode(),
                    remoteAddress(request), elapsedMs(started));
            return result;
        });
    }

    private static String extractToken(HttpRequest<?> request) {
        Optional<String> direct = request.getHeaders().findFirst("X-Admin-Token");
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<String> authorization = request.getHeaders().findFirst(HttpHeaders.AUTHORIZATION);
        if (authorization.isPresent() && authorization.get().regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.get().substring(7);
        }
        return "";
    }

    private static String reason(HttpRequest<?> request) {
        String value = request.getHeaders().get(REASON_HEADER);
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= MAX_REASON_LENGTH ? value.trim() : value.substring(0, MAX_REASON_LENGTH).trim();
    }

    private static boolean isWrite(io.micronaut.http.HttpMethod method) {
        return method == io.micronaut.http.HttpMethod.POST
                || method == io.micronaut.http.HttpMethod.PUT
                || method == io.micronaut.http.HttpMethod.DELETE
                || method == io.micronaut.http.HttpMethod.PATCH;
    }

    private static String operationOf(String path) {
        if (path == null) {
            return "";
        }
        String relative = ApiRoutes.relativeToVersion(ApiRoutes.ADMIN_ROOT, path);
        return relative.startsWith("/") ? relative.substring(1) : relative;
    }

    private static String requestId(HttpRequest<?> request) {
        Optional<String> supplied = request.getHeaders().findFirst("X-Request-ID");
        if (supplied.isPresent() && supplied.get().matches("[A-Za-z0-9._-]{1,64}")) {
            return supplied.get();
        }
        return UUID.randomUUID().toString();
    }

    private static String remoteAddress(HttpRequest<?> request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private MutableHttpResponse<?> errorResponse(HttpStatus status, String requestId,
                                                 String code, String message) {
        return HttpResponse.status(status)
                .body(Map.of("error", code, "message", message, "requestId", requestId))
                .header("X-Request-Id", requestId);
    }

    private String message(String key) {
        return i18n.message(key);
    }
}
