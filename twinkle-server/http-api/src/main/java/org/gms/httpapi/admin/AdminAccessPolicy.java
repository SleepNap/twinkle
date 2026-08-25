package org.gms.httpapi.admin;

import io.micronaut.http.HttpMethod;
import org.gms.httpapi.version.ApiRoutes;

/** 将各 {@code /admin/vN} 稳定 URL 契约映射到最小权限点。 */
public final class AdminAccessPolicy {

    public Policy resolve(HttpMethod method, String path) {
        String relativePath = ApiRoutes.relativeToVersion(ApiRoutes.ADMIN_ROOT, path);
        if ("/auth/login".equals(relativePath)) {
            return new Policy(true, "");
        }
        // auth 自身端点（logout/me）只需已认证，无额外权限。
        if (relativePath.startsWith("/auth/")) {
            return new Policy(false, "");
        }
        // 封包内容属于高敏诊断数据，读取和启停都要求独立权限。
        if (relativePath.startsWith("/packet-traces")) {
            return new Policy(false, AdminPermission.PACKET_TRACE);
        }
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            if ("/config".equals(relativePath)) {
                return new Policy(false, AdminPermission.CONFIG_WRITE);
            }
            if ("/kick".equals(relativePath)) {
                return new Policy(false, AdminPermission.PLAYER_KICK);
            }
            if ("/reload/logic".equals(relativePath)) {
                return new Policy(false, AdminPermission.RELOAD_LOGIC);
            }
            if ("/reload/scripts".equals(relativePath)) {
                return new Policy(false, AdminPermission.RELOAD_SCRIPTS);
            }
            if ("/reload/wz".equals(relativePath)) {
                return new Policy(false, AdminPermission.RELOAD_WZ);
            }
            if ("/restart".equals(relativePath) || "/restart/netty".equals(relativePath)) {
                return new Policy(false, AdminPermission.RESTART);
            }
            if (relativePath.startsWith("/tasks")
                    || relativePath.startsWith("/schedules")) {
                return new Policy(false, AdminPermission.TASK_MANAGE);
            }
            if (relativePath.startsWith("/billing")) {
                return new Policy(false, AdminPermission.BILLING_MANAGE);
            }
            if (relativePath.startsWith("/roles")
                    || (relativePath.startsWith("/accounts/") && relativePath.endsWith("/roles"))) {
                return new Policy(false, AdminPermission.ROLE_MANAGE);
            }
            if (relativePath.startsWith("/accounts")) {
                return new Policy(false, AdminPermission.ACCOUNT_MANAGE);
            }
        }
        return new Policy(false, AdminPermission.READ);
    }

    /** publicEndpoint 只用于无需认证的登录端点。 */
    public record Policy(boolean publicEndpoint, String requiredPermission) {
    }
}
