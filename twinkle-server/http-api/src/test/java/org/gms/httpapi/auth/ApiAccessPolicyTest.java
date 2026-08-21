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
}
