package org.gms.httpapi.api.v1.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/** v1 API-key 签发请求。 */
public record IssueApiKeyRequest(
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                         minLength = 1, maxLength = 128)
                                 String displayName,
                                 Long ownerAccountId,
                                 @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                                 Set<String> scopes,
                                 String expiresAt) {
}
