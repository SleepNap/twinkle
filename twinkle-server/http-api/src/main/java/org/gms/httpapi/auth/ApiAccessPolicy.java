package org.gms.httpapi.auth;

import io.micronaut.http.HttpMethod;

/** 将稳定 URL 契约映射到最小权限 scope。 */
public final class ApiAccessPolicy {

    public Policy resolve(HttpMethod method, String path) {
        if ("/api/v1/openapi.yaml".equals(path)) {
            return new Policy(true, "");
        }
        if (path.startsWith("/api/v1/auth/keys")) {
            return new Policy(false, ApiScopes.KEYS_MANAGE);
        }
        if (path.startsWith("/api/v1/identity/")
                || path.startsWith("/api/v1/capabilities")
                || path.startsWith("/api/v1/tool-executions")) {
            return new Policy(false, "");
        }
        if (path.startsWith("/api/v1/ai/")) {
            return new Policy(false, ApiScopes.AI_USE);
        }
        if ("/api/v1/online".equals(path)) {
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
