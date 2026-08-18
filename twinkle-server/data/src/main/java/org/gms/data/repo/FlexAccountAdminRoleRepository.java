package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.AccountAdminRole;
import org.gms.data.mapper.AccountAdminRoleMapper;

import java.util.List;

/** MyBatis-Flex 账号-管理员角色关联仓储实现。 */
public final class FlexAccountAdminRoleRepository implements AccountAdminRoleRepository {

    private final AccountAdminRoleMapper mapper;

    public FlexAccountAdminRoleRepository(AccountAdminRoleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<AccountAdminRole> findByAccountId(Long accountId) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(AccountAdminRole::getAccountId).eq(accountId));
    }

    @Override
    public void insert(AccountAdminRole relation) {
        mapper.insertSelective(relation);
    }

    @Override
    public void deleteByAccountId(Long accountId) {
        mapper.deleteByQuery(QueryWrapper.create()
                .where(AccountAdminRole::getAccountId).eq(accountId));
    }

    @Override
    public long count() {
        return mapper.selectCountByQuery(QueryWrapper.create());
    }
}
