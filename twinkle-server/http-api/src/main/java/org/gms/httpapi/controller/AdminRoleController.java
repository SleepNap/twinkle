package org.gms.httpapi.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import org.gms.data.entity.Account;
import org.gms.data.entity.AccountAdminRole;
import org.gms.data.entity.AdminRole;
import org.gms.data.repo.AccountAdminRoleRepository;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AdminRoleRepository;
import org.gms.httpapi.admin.AdminPermission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 控制台 RBAC 管理：角色 CRUD 与账号角色分配（需 {@code admin.role:manage} 权限，过滤器 RBAC 校验）。
 */
@Controller("/admin/v1")
@Produces(MediaType.APPLICATION_JSON)
public final class AdminRoleController {

    private static final String SUPER_ADMIN_ROLE = "super_admin";

    private final AdminRoleRepository roleRepository;
    private final AccountAdminRoleRepository accountRoleRepository;
    private final AccountRepository accountRepository;

    public AdminRoleController(AdminRoleRepository roleRepository,
                               AccountAdminRoleRepository accountRoleRepository,
                               AccountRepository accountRepository) {
        this.roleRepository = roleRepository;
        this.accountRoleRepository = accountRoleRepository;
        this.accountRepository = accountRepository;
    }

    /** 角色列表。 */
    @Get("/roles")
    public Map<String, Object> roles() {
        return Map.of("roles", roleRepository.findAll().stream().map(this::roleMap).toList());
    }

    /** 创建角色。body: {roleCode, displayName, description, permissions}。 */
    @Post("/roles")
    public HttpResponse<?> createRole(@Body Map<String, Object> body) {
        String roleCode = stringField(body, "roleCode");
        if (roleCode == null || roleCode.isBlank() || !roleCode.matches("[a-z0-9_]{1,64}")) {
            return HttpResponse.badRequest(Map.of("error", "invalid_role_code"));
        }
        if (roleRepository.findByRoleCode(roleCode).isPresent()) {
            return HttpResponse.status(HttpStatus.CONFLICT).body(Map.of("error", "role_code_exists"));
        }
        String permissions = stringField(body, "permissions");
        if (permissions == null || permissions.isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "permissions_required"));
        }
        if (!validPermissions(permissions)) {
            return HttpResponse.badRequest(Map.of("error", "invalid_permissions"));
        }
        AdminRole role = new AdminRole();
        role.setRoleCode(roleCode);
        role.setDisplayName(safe(stringField(body, "displayName"), 128));
        role.setDescription(safe(stringField(body, "description"), 512));
        role.setPermissions(permissions.trim());
        roleRepository.insert(role);
        return HttpResponse.ok(roleMap(role));
    }

    /** 更新角色（displayName/description/permissions）。super_admin 权限恒为 {@code *} 不可改。 */
    @Put("/roles/{roleId}")
    public HttpResponse<?> updateRole(@PathVariable long roleId, @Body Map<String, Object> body) {
        AdminRole role = roleRepository.findById(roleId).orElse(null);
        if (role == null) {
            return HttpResponse.notFound(Map.of("error", "role_not_found"));
        }
        String permissions = stringField(body, "permissions");
        if (permissions != null && !permissions.isBlank()) {
            if (SUPER_ADMIN_ROLE.equals(role.getRoleCode()) && !AdminPermission.ALL.equals(permissions.trim())) {
                return HttpResponse.badRequest(Map.of("error", "super_admin_permissions_fixed"));
            }
            if (!validPermissions(permissions)) {
                return HttpResponse.badRequest(Map.of("error", "invalid_permissions"));
            }
            role.setPermissions(permissions.trim());
        }
        if (body.get("displayName") != null) {
            role.setDisplayName(safe(stringField(body, "displayName"), 128));
        }
        if (body.get("description") != null) {
            role.setDescription(safe(stringField(body, "description"), 512));
        }
        roleRepository.update(role);
        return HttpResponse.ok(roleMap(role));
    }

    /** 账号当前角色列表。 */
    @Get("/accounts/{accountId}/roles")
    public Map<String, Object> accountRoles(@PathVariable long accountId) {
        List<Map<String, Object>> roles = new ArrayList<>();
        for (AccountAdminRole relation : accountRoleRepository.findByAccountId(accountId)) {
            roleRepository.findById(relation.getRoleId()).ifPresent(role -> roles.add(roleMap(role)));
        }
        return Map.of("accountId", accountId, "roles", roles);
    }

    /** 设置账号角色（覆盖式）。body: {roleIds: [1,2]}。 */
    @Put("/accounts/{accountId}/roles")
    public HttpResponse<?> setAccountRoles(@PathVariable long accountId, @Body Map<String, Object> body) {
        if (accountRepository.findById(accountId).isEmpty()) {
            return HttpResponse.notFound(Map.of("error", "account_not_found"));
        }
        Object raw = body.get("roleIds");
        if (!(raw instanceof List<?> ids)) {
            return HttpResponse.badRequest(Map.of("error", "role_ids_required"));
        }
        List<Long> roleIds = new ArrayList<>();
        for (Object id : ids) {
            if (!(id instanceof Number n)) {
                return HttpResponse.badRequest(Map.of("error", "invalid_role_ids"));
            }
            long roleId = n.longValue();
            if (roleRepository.findById(roleId).isEmpty()) {
                return HttpResponse.badRequest(Map.of("error", "role_not_found"));
            }
            roleIds.add(roleId);
        }
        accountRoleRepository.deleteByAccountId(accountId);
        for (Long roleId : roleIds) {
            AccountAdminRole relation = new AccountAdminRole();
            relation.setAccountId(accountId);
            relation.setRoleId(roleId);
            accountRoleRepository.insert(relation);
        }
        return HttpResponse.ok(accountRoles(accountId));
    }

    private Map<String, Object> roleMap(AdminRole role) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", role.getId());
        result.put("roleCode", role.getRoleCode());
        result.put("displayName", role.getDisplayName());
        result.put("description", role.getDescription());
        result.put("permissions", role.getPermissions());
        return result;
    }

    private static boolean validPermissions(String permissions) {
        for (String part : permissions.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!AdminPermission.ALL.equals(value) && !AdminPermission.SUPPORTED.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static String stringField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static String safe(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
