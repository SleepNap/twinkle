package org.gms.httpapi.api.v1.dto.response;

import java.util.Set;

/** v1 API-key 安全摘要，不包含 token 或哈希。 */
public record ApiKeySummaryResponse(Long id, String credentialId, String keyPrefix,
                                    String displayName, String subjectId, Long ownerAccountId,
                                    Set<String> scopes, String serverId, String createdAt,
                                    String expiresAt, String disabledAt, String revokedAt,
                                    String rotatedFromPrefix, String lastUsedAt) {
}
