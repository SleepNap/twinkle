package org.gms.httpapi.version;

import org.gms.httpapi.auth.ApiErrorContractRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiVersionCatalogTest {

    private final ApiVersionCatalog catalog = new ApiVersionCatalog();

    @Test
    void registersV1ForEveryPlane() {
        for (ApiPlane plane : ApiPlane.values()) {
            assertThat(catalog.find(plane, 1))
                    .get()
                    .extracting(ApiVersionDefinition::status)
                    .isEqualTo(ApiVersionStatus.ACTIVE);
        }
    }

    @Test
    void recognizesVersionedPathButDoesNotPublishUnknownMajor() {
        assertThat(catalog.resolve("/api/v2/online"))
                .get()
                .matches(resolved -> resolved.major() == 2 && !resolved.registered());
    }

    @Test
    void everyPublishedPublicVersionHasAnExplicitErrorContract() {
        ApiErrorContractRegistry errorContracts = new ApiErrorContractRegistry();
        assertThat(catalog.definitions(ApiPlane.PUBLIC))
                .allMatch(definition -> errorContracts.supports(definition.major()));
    }
}
