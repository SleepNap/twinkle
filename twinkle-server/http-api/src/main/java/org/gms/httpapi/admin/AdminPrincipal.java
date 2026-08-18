package org.gms.httpapi.admin;

import java.util.Set;

/** 一次通过管理员会话认证的调用者投影。 */
public record AdminPrincipal(Long accountId, String accountName, Long sessionId,
                             Set<String> permissions) {

    public boolean permits(String requiredPermission) {
        if (requiredPermission == null || requiredPermission.isBlank()) {
            return true;
        }
        return permissions.contains(AdminPermission.ALL)
                || permissions.contains(requiredPermission);
    }
}
