package org.gms.httpapi.auth;

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
import org.reactivestreams.Publisher;
import org.gms.httpapi.version.ApiHeaders;
import org.gms.httpapi.limit.ApiRateLimiter;
import org.gms.i18n.I18nService;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 公共 API 与内部 API 共用的 API-key 认证、scope 授权和全链路审计。 */
@Filter({ApiRoutes.PUBLIC_ROOT + "/**", ApiRoutes.INTERNAL_ROOT + "/**"})
public final class ApiKeyAuthFilter implements HttpServerFilter, Ordered {

    public static final String PRINCIPAL_ATTRIBUTE = "twinkle.api.principal";
    public static final String REQUEST_ID_ATTRIBUTE = "twinkle.api.request-id";
    public static final String SUBJECT_ID_ATTRIBUTE = "twinkle.api.subject-id";
    public static final String CREDENTIAL_ID_ATTRIBUTE = "twinkle.api.credential-id";

    private final ApiKeyService apiKeyService;
    private final ApiAuditService auditService;
    private final ApiAccessPolicy accessPolicy;
    private final ApiRateLimiter rateLimiter;
    private final ApiErrorContractRegistry errorContracts;
    private final I18nService i18n;

    public ApiKeyAuthFilter(ApiKeyService apiKeyService, ApiAuditService auditService,
                            ApiAccessPolicy accessPolicy, ApiRateLimiter rateLimiter,
                            ApiErrorContractRegistry errorContracts, I18nService i18n) {
        this.apiKeyService = apiKeyService;
        this.auditService = auditService;
        this.accessPolicy = accessPolicy;
        this.rateLimiter = rateLimiter;
        this.errorContracts = errorContracts;
        this.i18n = i18n;
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.before();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        ApiAccessPolicy.Policy policy = accessPolicy.resolve(request.getMethod(), request.getPath());
        if (policy.publicEndpoint()) {
            return chain.proceed(request);
        }

        long started = System.nanoTime();
        String requestId = requestId(request);
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        if (!rateLimiter.tryConsume("preauth:" + remoteAddress(request))) {
            auditService.record(requestId, null, request.getMethodName(), request.getPath(),
                    policy.requiredScope(), "rate_limited", HttpStatus.TOO_MANY_REQUESTS.getCode(),
                    remoteAddress(request), elapsedMs(started));
            return Publishers.just(errorContracts.response(request.getPath(), HttpStatus.TOO_MANY_REQUESTS,
                            requestId, null, "rate_limited", message("api.error.preauth_rate_limited"),
                            true, Map.of())
                    .header(ApiHeaders.REQUEST_ID, requestId)
                    .header(ApiHeaders.CONTRACT_VERSION, errorContracts.contractVersion(request.getPath())));
        }
        Optional<ApiPrincipal> authenticated = apiKeyService.authenticate(extractToken(request));
        if (authenticated.isEmpty()) {
            auditService.record(requestId, null, request.getMethodName(), request.getPath(),
                    policy.requiredScope(), "unauthenticated", HttpStatus.UNAUTHORIZED.getCode(),
                    remoteAddress(request), elapsedMs(started));
            MutableHttpResponse<?> response = errorContracts.response(request.getPath(), HttpStatus.UNAUTHORIZED,
                    requestId, null, "unauthenticated", message("api.error.unauthenticated"),
                    false, Map.of())
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    .header(ApiHeaders.REQUEST_ID, requestId)
                    .header(ApiHeaders.CONTRACT_VERSION, errorContracts.contractVersion(request.getPath()));
            return Publishers.just(response);
        }

        ApiPrincipal principal = authenticated.get();
        request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
        request.setAttribute(SUBJECT_ID_ATTRIBUTE, principal.subjectId());
        request.setAttribute(CREDENTIAL_ID_ATTRIBUTE, principal.credentialId());
        if (!principal.permits(policy.requiredScope())) {
            auditService.record(requestId, principal, request.getMethodName(), request.getPath(),
                    policy.requiredScope(), "forbidden", HttpStatus.FORBIDDEN.getCode(),
                    remoteAddress(request), elapsedMs(started));
            MutableHttpResponse<?> response = errorContracts.response(request.getPath(), HttpStatus.FORBIDDEN,
                    requestId, null, "permission_denied", message("api.error.permission_denied"),
                    false, Map.of("requiredScopes", List.of(policy.requiredScope())))
                    .header(ApiHeaders.REQUEST_ID, requestId)
                    .header(ApiHeaders.CONTRACT_VERSION, errorContracts.contractVersion(request.getPath()));
            return Publishers.just(response);
        }

        apiKeyService.markUsed(principal);
        Publisher<MutableHttpResponse<?>> response = chain.proceed(request);
        return Publishers.map(response, result -> {
            String effectiveRequestId = request.getAttribute(REQUEST_ID_ATTRIBUTE, String.class)
                    .orElse(requestId);
            result.header(ApiHeaders.REQUEST_ID, effectiveRequestId);
            result.header(ApiHeaders.CONTRACT_VERSION, errorContracts.contractVersion(request.getPath()));
            auditService.record(effectiveRequestId, principal, request.getMethodName(), request.getPath(),
                    policy.requiredScope(), "allowed", result.getStatus().getCode(),
                    remoteAddress(request), elapsedMs(started));
            return result;
        });
    }

    private static String extractToken(HttpRequest<?> request) {
        Optional<String> direct = request.getHeaders().findFirst("X-API-Key");
        if (direct.isPresent()) {
            return direct.get();
        }
        Optional<String> authorization = request.getHeaders().findFirst(HttpHeaders.AUTHORIZATION);
        if (authorization.isPresent() && authorization.get().regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.get().substring(7);
        }
        return "";
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

    private String message(String key) {
        return i18n.message(key);
    }
}
