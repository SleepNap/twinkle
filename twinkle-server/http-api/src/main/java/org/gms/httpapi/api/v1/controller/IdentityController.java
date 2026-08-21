package org.gms.httpapi.api.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import org.gms.httpapi.auth.ApiKeyAuthFilter;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.api.v1.contract.ApiContract;
import org.gms.httpapi.api.v1.dto.response.IdentityResponse;
import org.gms.httpapi.api.v1.mapper.PublicApiV1Mapper;
import org.gms.httpapi.identity.ServerIdentity;

/** 当前 Credential 的安全身份与权限快照；仅供体验预检，不替代执行时授权。 */
@Controller(ApiRoutes.PUBLIC_V1 + "/identity")
@Produces(MediaType.APPLICATION_JSON)
public final class IdentityController {

    private final ServerIdentity serverIdentity;

    public IdentityController(ServerIdentity serverIdentity) {
        this.serverIdentity = serverIdentity;
    }

    @Get("/me")
    public IdentityResponse me(HttpRequest<?> request) {
        ApiPrincipal principal = principal(request);
        return PublicApiV1Mapper.identity(ApiContract.VERSION, principal, serverIdentity);
    }

    private static ApiPrincipal principal(HttpRequest<?> request) {
        return request.getAttribute(ApiKeyAuthFilter.PRINCIPAL_ATTRIBUTE, ApiPrincipal.class)
                .orElseThrow(() -> new IllegalStateException("API principal missing"));
    }
}
