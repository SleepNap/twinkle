package org.gms.httpapi.controller;

import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import org.gms.httpapi.admin.AdminAuthFilter;
import org.gms.httpapi.admin.AdminPrincipal;
import org.gms.httpapi.admin.AdminSessionService;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;

/** Web 控制台管理员登录、登出与当前身份（强鉴权）。 */
@Controller("/admin/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
public final class AdminAuthController {

    private final AdminSessionService sessionService;

    public AdminAuthController(AdminSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 登录（public，无需 token）。body: {name, password}。 */
    @Post("/login")
    public HttpResponse<?> login(HttpRequest<?> request, @Body Map<String, String> body) {
        String name = body.get("name");
        String password = body.get("password");
        if (name == null || name.isBlank() || password == null || password.isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "admin_login_required"));
        }
        Optional<AdminSessionService.LoginResult> result =
                sessionService.login(name, password, remoteAddress(request));
        if (result.isEmpty()) {
            return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "admin_login_invalid"));
        }
        AdminSessionService.LoginResult login = result.get();
        return HttpResponse.ok(Map.of(
                "token", login.token(),
                "accountName", login.principal().accountName(),
                "permissions", login.principal().permissions()));
    }

    /** 登出（需认证）：吊销当前会话。 */
    @Post("/logout")
    public HttpResponse<?> logout(HttpRequest<?> request) {
        sessionService.logout(extractToken(request));
        return HttpResponse.ok(Map.of("loggedOut", true));
    }

    /** 当前身份（需认证）：由过滤器写入的 principal 投影。 */
    @Get("/me")
    public HttpResponse<?> me(HttpRequest<?> request) {
        AdminPrincipal principal = request.getAttribute(
                AdminAuthFilter.PRINCIPAL_ATTRIBUTE, AdminPrincipal.class).orElse(null);
        if (principal == null) {
            return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "admin_unauthenticated"));
        }
        return HttpResponse.ok(Map.of(
                "accountId", principal.accountId(),
                "accountName", principal.accountName(),
                "permissions", principal.permissions()));
    }

    private static String extractToken(HttpRequest<?> request) {
        String direct = request.getHeaders().get("X-Admin-Token");
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7);
        }
        return "";
    }

    private static String remoteAddress(HttpRequest<?> request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return remote.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
