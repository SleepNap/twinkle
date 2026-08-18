package org.gms.httpapi.admin;

import io.micronaut.http.HttpMethod;

/** 将 {@code /admin/v1} 稳定 URL 契约映射到最小权限点。 */
public final class AdminAccessPolicy {

    public Policy resolve(HttpMethod method, String path) {
        if ("/admin/v1/auth/login".equals(path)) {
            return new Policy(true, "");
        }
        // auth 自身端点（logout/me）只需已认证，无额外权限。
        if (path.startsWith("/admin/v1/auth/")) {
            return new Policy(false, "");
        }
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            if ("/admin/v1/config".equals(path)) {
                return new Policy(false, AdminPermission.CONFIG_WRITE);
            }
            if ("/admin/v1/kick".equals(path)) {
                return new Policy(false, AdminPermission.PLAYER_KICK);
            }
            if ("/admin/v1/reload/logic".equals(path)) {
                return new Policy(false, AdminPermission.RELOAD_LOGIC);
            }
            if ("/admin/v1/reload/scripts".equals(path)) {
                return new Policy(false, AdminPermission.RELOAD_SCRIPTS);
            }
            if ("/admin/v1/restart".equals(path)) {
                return new Policy(false, AdminPermission.RESTART);
            }
            if (path.startsWith("/admin/v1/tasks") || path.startsWith("/admin/v1/schedules")) {
                return new Policy(false, AdminPermission.TASK_MANAGE);
            }
            if (path.startsWith("/admin/v1/billing")) {
                return new Policy(false, AdminPermission.BILLING_MANAGE);
            }
            if (path.startsWith("/admin/v1/roles")
                    || (path.startsWith("/admin/v1/accounts/") && path.endsWith("/roles"))) {
                return new Policy(false, AdminPermission.ROLE_MANAGE);
            }
        }
        return new Policy(false, AdminPermission.READ);
    }

    /** publicEndpoint 只用于无需认证的登录端点。 */
    public record Policy(boolean publicEndpoint, String requiredPermission) {
    }
}
