package org.gms.httpapi.api.v1.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

/** v1 API-key 签发/轮换响应；token 只在本响应中出现。 */
public record IssuedApiKeyResponse(Long id, String credentialId, String keyPrefix,
                                   @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String token,
                                   String displayName, String subjectId, Long ownerAccountId,
                                   Set<String> scopes, String serverId, String createdAt,
                                   String expiresAt, String rotatedFromPrefix) {
}
