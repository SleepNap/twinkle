package org.gms.httpapi.auth;

import io.micronaut.http.HttpMethod;
import org.gms.httpapi.version.ApiRoutes;

/** 将稳定 URL 契约映射到最小权限 scope。 */
public final class ApiAccessPolicy {

    public Policy resolve(HttpMethod method, String path) {
        if (path != null && path.startsWith(ApiRoutes.INTERNAL_ROOT + "/")) {
            return resolveInternal(method,
                    ApiRoutes.relativeToVersion(ApiRoutes.INTERNAL_ROOT, path));
        }
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

    private static Policy resolveInternal(HttpMethod method, String relativePath) {
        if ("/health".equals(relativePath)) {
            return new Policy(false, ApiScopes.SERVER_HEALTH_READ);
        }
        if ("/online".equals(relativePath)) {
            return new Policy(false, ApiScopes.PLAYER_ONLINE_READ);
        }
        if ("/reload/in-flight".equals(relativePath)) {
            return new Policy(false, ApiScopes.SERVER_RELOAD_READ);
        }
        if ("/reload".equals(relativePath) && method == HttpMethod.POST) {
            return new Policy(false, ApiScopes.SERVER_RELOAD_WRITE);
        }
        if ("/config".equals(relativePath) && method == HttpMethod.POST) {
            return new Policy(false, ApiScopes.SERVER_CONFIG_WRITE);
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
