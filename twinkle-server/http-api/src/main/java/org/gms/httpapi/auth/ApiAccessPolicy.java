package org.gms.httpapi.auth;

import io.micronaut.http.HttpMethod;
import org.gms.httpapi.version.ApiRoutes;

/** 将稳定 URL 契约映射到最小权限 scope。 */
public final class ApiAccessPolicy {

    public Policy resolve(HttpMethod method, String path) {
        String relativePath = ApiRoutes.relativeToVersion(ApiRoutes.PUBLIC_ROOT, path);
        if ("/openapi.yaml".equals(relativePath)) {
            return new Policy(true, "");
        }
        if (relativePath.startsWith("/auth/keys")) {
            return new Policy(false, ApiScopes.KEYS_MANAGE);
        }
        if (relativePath.startsWith("/identity/")
                || relativePath.startsWith("/capabilities")
                || relativePath.startsWith("/tool-executions")) {
            return new Policy(false, "");
        }
        if ("/online".equals(relativePath)) {
            return new Policy(false, ApiScopes.PLAYER_ONLINE_READ);
        }
        if (method == HttpMethod.GET || method == HttpMethod.HEAD) {
            return new Policy(false, ApiScopes.GAME_READ);
        }
        return new Policy(false, ApiScopes.GAME_WRITE);
    }

    /** publicEndpoint 只用于无敏感数据的契约发现。 */
    public record Policy(boolean publicEndpoint, String requiredScope) {
    }
}
