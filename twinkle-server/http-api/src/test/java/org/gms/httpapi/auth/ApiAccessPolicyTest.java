package org.gms.httpapi.auth;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAccessPolicyTest {

    private final ApiAccessPolicy policy = new ApiAccessPolicy();

    @Test
    void inheritedReadRouteKeepsSameScopeAcrossMajors() {
        assertThat(policy.resolve(HttpMethod.GET, "/api/v1/online").requiredScope())
                .isEqualTo(ApiScopes.PLAYER_ONLINE_READ);
        assertThat(policy.resolve(HttpMethod.GET, "/api/v2/online").requiredScope())
                .isEqualTo(ApiScopes.PLAYER_ONLINE_READ);
    }

    @Test
    void eachVersionOpenApiContractIsPublic() {
        assertThat(policy.resolve(HttpMethod.GET, "/api/v1/openapi.yaml").publicEndpoint()).isTrue();
        assertThat(policy.resolve(HttpMethod.GET, "/api/v2/openapi.yaml").publicEndpoint()).isTrue();
    }

    @Test
    void internalRoutesRequireDedicatedScopes() {
        assertThat(policy.resolve(HttpMethod.GET, "/internal/v1/health").requiredScope())
                .isEqualTo(ApiScopes.SERVER_HEALTH_READ);
        assertThat(policy.resolve(HttpMethod.GET, "/internal/v1/online").requiredScope())
                .isEqualTo(ApiScopes.PLAYER_ONLINE_READ);
        assertThat(policy.resolve(HttpMethod.GET, "/internal/v1/reload/in-flight").requiredScope())
                .isEqualTo(ApiScopes.SERVER_RELOAD_READ);
        assertThat(policy.resolve(HttpMethod.POST, "/internal/v1/reload").requiredScope())
                .isEqualTo(ApiScopes.SERVER_RELOAD_WRITE);
        assertThat(policy.resolve(HttpMethod.POST, "/internal/v1/config").requiredScope())
                .isEqualTo(ApiScopes.SERVER_CONFIG_WRITE);
    }
}
