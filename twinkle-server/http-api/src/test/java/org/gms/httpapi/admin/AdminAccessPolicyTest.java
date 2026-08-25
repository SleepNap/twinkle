package org.gms.httpapi.admin;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 各 /admin/vN URL 契约到权限点的映射测试。 */
class AdminAccessPolicyTest {

    private final AdminAccessPolicy policy = new AdminAccessPolicy();

    @Test
    void login_isPublic() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/auth/login");
        assertThat(p.publicEndpoint()).isTrue();
    }

    @Test
    void authSelf_requiresNoPermission() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/auth/logout");
        assertThat(p.publicEndpoint()).isFalse();
        assertThat(p.requiredPermission()).isEmpty();
    }

    @Test
    void read_requiresAdminRead() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.GET, "/admin/v1/health");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.READ);
    }

    @Test
    void configWrite_requiresConfigWrite() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/config");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.CONFIG_WRITE);
    }

    @Test
    void kick_requiresPlayerKick() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/kick");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.PLAYER_KICK);
    }

    @Test
    public void packetTraceWriteRequiresDedicatedPermission() {
        AdminAccessPolicy.Policy start = policy.resolve(
                HttpMethod.PUT, "/admin/v1/packet-traces/42");
        AdminAccessPolicy.Policy stop = policy.resolve(
                HttpMethod.DELETE, "/admin/v1/packet-traces/42");
        AdminAccessPolicy.Policy read = policy.resolve(
                HttpMethod.GET, "/admin/v1/packet-traces/42");

        assertThat(start.requiredPermission()).isEqualTo(AdminPermission.PACKET_TRACE);
        assertThat(stop.requiredPermission()).isEqualTo(AdminPermission.PACKET_TRACE);
        assertThat(read.requiredPermission()).isEqualTo(AdminPermission.PACKET_TRACE);
    }

    @Test
    void wzReload_requiresDedicatedPermission() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/reload/wz");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.RELOAD_WZ);
    }

    @Test
    void nettyRestart_requiresRestartPermission() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/restart/netty");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.RESTART);
    }

    @Test
    void billingWrite_requiresBillingManage() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/billing/accounts/1/adjust");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.BILLING_MANAGE);
    }

    @Test
    void roleWrite_requiresRoleManage() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/roles");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.ROLE_MANAGE);
    }

    @Test
    void accountRoleAssign_requiresRoleManage() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.PUT, "/admin/v1/accounts/7/roles");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.ROLE_MANAGE);
    }

    @Test
    void accountRestriction_requiresAccountManage() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.PUT, "/admin/v1/accounts/7/restrictions");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.ACCOUNT_MANAGE);
    }

    @Test
    void accountForceOffline_requiresAccountManage() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/accounts/7/force-offline");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.ACCOUNT_MANAGE);
    }

    @Test
    void taskWrite_requiresTaskManage() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/tasks/abc/retry");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.TASK_MANAGE);
    }

    @Test
    void unchangedV2RouteInheritsPermissionMapping() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.PUT, "/admin/v2/accounts/7/restrictions");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.ACCOUNT_MANAGE);
    }

}
