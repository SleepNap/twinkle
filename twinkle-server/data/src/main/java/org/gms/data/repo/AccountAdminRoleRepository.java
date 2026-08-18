package org.gms.data.repo;

import org.gms.data.entity.AccountAdminRole;

import java.util.List;

/** 账号-管理员角色关联持久化契约（RBAC）。 */
public interface AccountAdminRoleRepository {

    public List<AccountAdminRole> findByAccountId(Long accountId);

    public void insert(AccountAdminRole relation);

    public void deleteByAccountId(Long accountId);

    public long count();
}
