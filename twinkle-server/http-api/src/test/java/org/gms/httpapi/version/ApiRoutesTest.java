package org.gms.httpapi.version;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRoutesTest {

    @Test
    void extractsMajorAndRelativePathWithoutBindingPolicyToV1() {
        assertThat(ApiRoutes.major(ApiRoutes.PUBLIC_ROOT, "/api/v2/accounts/7")).isEqualTo(2);
        assertThat(ApiRoutes.relativeToVersion(ApiRoutes.PUBLIC_ROOT, "/api/v2/accounts/7"))
                .isEqualTo("/accounts/7");
    }

    @Test
    void rejectsMalformedVersionSegment() {
        assertThat(ApiRoutes.major(ApiRoutes.PUBLIC_ROOT, "/api/version2/accounts")).isEqualTo(-1);
        assertThat(ApiRoutes.major(ApiRoutes.PUBLIC_ROOT, "/api/v2beta/accounts")).isEqualTo(-1);
    }

    @Test
    void buildsPlaneSpecificVersionPrefixes() {
        assertThat(ApiRoutes.publicVersion(2)).isEqualTo("/api/v2");
        assertThat(ApiRoutes.adminVersion(3)).isEqualTo("/admin/v3");
        assertThat(ApiRoutes.internalVersion(4)).isEqualTo("/internal/v4");
    }
}
