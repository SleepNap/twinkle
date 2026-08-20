package org.gms.httpapi.admin;

import io.micronaut.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** /admin/v1 URL 契约到权限点的映射测试。 */
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
    void taskWrite_requiresTaskManage() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.POST, "/admin/v1/tasks/abc/retry");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.TASK_MANAGE);
    }

    @Test
    void aiPolicyWrite_requiresAiManage() {
        AdminAccessPolicy.Policy p = policy.resolve(HttpMethod.PUT, "/admin/v1/ai/policies/7");
        assertThat(p.requiredPermission()).isEqualTo(AdminPermission.AI_MANAGE);
    }

    /** AI 的读端点必须落在通用只读权限上，别被写分支吃掉——审计员也要能看运行态。 */
    @Test
    void aiStatusRead_requiresAdminRead() {
        assertThat(policy.resolve(HttpMethod.GET, "/admin/v1/ai/status").requiredPermission())
                .isEqualTo(AdminPermission.READ);
        assertThat(policy.resolve(HttpMethod.GET, "/admin/v1/ai/usage").requiredPermission())
                .isEqualTo(AdminPermission.READ);
    }
}
