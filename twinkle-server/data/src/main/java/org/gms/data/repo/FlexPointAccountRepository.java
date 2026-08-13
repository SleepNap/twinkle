package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.PointAccount;
import org.gms.data.mapper.PointAccountMapper;

import java.util.List;
import java.util.Optional;

/** MyBatis-Flex 积分账户仓储实现。 */
public final class FlexPointAccountRepository implements PointAccountRepository {

    private final PointAccountMapper mapper;

    public FlexPointAccountRepository(PointAccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PointAccount> findByAccountId(Long accountId) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(PointAccount::getAccountId).eq(accountId)));
    }

    @Override
    public void insert(PointAccount account) {
        mapper.insertSelective(account);
    }

    @Override
    public void update(PointAccount account) {
        mapper.update(account);
    }

    @Override
    public List<PointAccount> findAll() {
        return mapper.selectListByQuery(QueryWrapper.create().orderBy(PointAccount::getId, true));
    }
}
