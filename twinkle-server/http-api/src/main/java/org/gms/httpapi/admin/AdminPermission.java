package org.gms.httpapi.admin;

import java.util.Set;

/** Web 控制台管理面的稳定权限点（RBAC）。读操作统一 {@code admin:read}，写操作按端点细分。 */
public final class AdminPermission {

    public static final String READ = "admin:read";
    public static final String CONFIG_WRITE = "admin.config:write";
    public static final String PLAYER_KICK = "admin.player:kick";
    public static final String RELOAD_LOGIC = "admin.reload:logic";
    public static final String RELOAD_SCRIPTS = "admin.reload:scripts";
    public static final String RESTART = "admin.restart";
    public static final String TASK_MANAGE = "admin.task:manage";
    public static final String BILLING_MANAGE = "admin.billing:manage";
    public static final String ROLE_MANAGE = "admin.role:manage";
    public static final String ACCOUNT_MANAGE = "admin.account:manage";

    /** 通配：超级管理员。 */
    public static final String ALL = "*";

    public static final Set<String> SUPPORTED = Set.of(
            READ, CONFIG_WRITE, PLAYER_KICK, RELOAD_LOGIC, RELOAD_SCRIPTS,
            RESTART, TASK_MANAGE, BILLING_MANAGE, ROLE_MANAGE, ACCOUNT_MANAGE);

    private AdminPermission() {
    }
}
