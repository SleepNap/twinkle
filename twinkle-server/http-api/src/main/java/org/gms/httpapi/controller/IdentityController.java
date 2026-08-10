package org.gms.httpapi.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import org.gms.httpapi.auth.ApiKeyAuthFilter;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.contract.ApiContract;
import org.gms.httpapi.identity.ServerIdentity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 当前 Credential 的安全身份与权限快照；仅供体验预检，不替代执行时授权。 */
@Controller("/api/v1/identity")
@Produces(MediaType.APPLICATION_JSON)
public final class IdentityController {

    private final ServerIdentity serverIdentity;

    public IdentityController(ServerIdentity serverIdentity) {
        this.serverIdentity = serverIdentity;
    }

    @Get("/me")
    public Map<String, Object> me(HttpRequest<?> request) {
        ApiPrincipal principal = principal(request);
        LinkedHashMap<String, Object> subject = new LinkedHashMap<>();
        subject.put("subjectId", principal.subjectId());
        subject.put("displayName", principal.subjectDisplayName());

        LinkedHashMap<String, Object> credential = new LinkedHashMap<>();
        credential.put("credentialId", principal.credentialId());
        credential.put("type", "api_key");
        credential.put("expiresAt", principal.expiresAt());

        LinkedHashMap<String, Object> selector = new LinkedHashMap<>();
        selector.put("type", "server");
        selector.put("mode", "ids");
        selector.put("ids", List.of(principal.serverId()));

        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", ApiContract.VERSION);
        result.put("subject", subject);
        result.put("credential", credential);
        result.put("server", serverIdentity.toSafeMap());
        result.put("effectiveScopes", principal.scopes());
        result.put("resourceSelectors", List.of(selector));
        result.put("permissionVersion", principal.permissionVersion());
        result.put("generatedAt", Instant.now().toString());
        return result;
    }

    private static ApiPrincipal principal(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE, ApiPrincipal.class)
                .orElseThrow(() -> new IllegalStateException("API principal missing"));
    }
}
