package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.AdminRole;
import org.gms.data.mapper.AdminRoleMapper;

import java.util.List;
import java.util.Optional;

/** MyBatis-Flex 管理员角色仓储实现。 */
public final class FlexAdminRoleRepository implements AdminRoleRepository {

    private final AdminRoleMapper mapper;

    public FlexAdminRoleRepository(AdminRoleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AdminRole> findAll() {
        return mapper.selectListByQuery(QueryWrapper.create()
                .orderBy(AdminRole::getId, true));
    }

    @Override
    public Optional<AdminRole> findByRoleCode(String roleCode) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(AdminRole::getRoleCode).eq(roleCode)));
    }

    @Override
    public Optional<AdminRole> findById(Long id) {
        return Optional.ofNullable(mapper.selectOneById(id));
    }

    @Override
    public void insert(AdminRole role) {
        mapper.insertSelective(role);
    }

    @Override
    public void update(AdminRole role) {
        mapper.update(role);
    }
}
