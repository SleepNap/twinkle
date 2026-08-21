package org.gms.httpapi.api.v1.dto.response;

import java.util.List;
import java.util.Set;

/** v1 Credential 身份与权限快照。 */
public record IdentityResponse(
        String contractVersion,
        Subject subject,
        Credential credential,
        Server server,
        Set<String> effectiveScopes,
        List<ResourceSelector> resourceSelectors,
        String permissionVersion,
        String generatedAt) {

    public record Subject(String subjectId, String displayName) {
    }

    public record Credential(String credentialId, String type, String expiresAt) {
    }

    public record Server(String serverId, String displayName, String environment, String version) {
    }

    public record ResourceSelector(String type, String mode, List<String> ids) {
    }
}
