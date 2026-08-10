package org.gms.httpapi.auth;

import java.util.Set;

/** 一次通过 API-key 认证的调用者投影。 */
public record ApiPrincipal(Long keyId, String credentialId, String keyPrefix,
                           String subjectId, String subjectDisplayName,
                           String displayName, Set<String> scopes, String serverId,
                           String expiresAt, String permissionVersion) {

    public boolean permits(String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank() || scopes.contains("*")
                || scopes.contains(requiredScope)) {
            return true;
        }
        return scopes.contains(ApiScopes.GAME_READ)
                && (ApiScopes.SERVER_HEALTH_READ.equals(requiredScope)
                || ApiScopes.PLAYER_ONLINE_READ.equals(requiredScope));
    }
}
