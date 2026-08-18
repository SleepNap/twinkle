package org.gms.data.repo;

import org.gms.data.entity.AdminRole;

import java.util.List;
import java.util.Optional;

/** 管理员角色持久化契约（RBAC）。 */
public interface AdminRoleRepository {

    public List<AdminRole> findAll();

    public Optional<AdminRole> findByRoleCode(String roleCode);

    public Optional<AdminRole> findById(Long id);

    public void insert(AdminRole role);

    public void update(AdminRole role);
}
