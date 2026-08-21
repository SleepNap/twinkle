package org.gms.httpapi.api.v1.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/** v1 API-key scope 更新请求。 */
public record UpdateApiKeyScopesRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Set<String> scopes) {
}
