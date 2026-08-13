package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.PointTransaction;
import org.gms.data.mapper.PointTransactionMapper;

import java.util.List;

/** MyBatis-Flex 积分流水仓储实现。 */
public final class FlexPointTransactionRepository implements PointTransactionRepository {

    private final PointTransactionMapper mapper;

    public FlexPointTransactionRepository(PointTransactionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(PointTransaction transaction) {
        mapper.insertSelective(transaction);
    }

    @Override
    public List<PointTransaction> findByAccountId(Long accountId) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(PointTransaction::getAccountId).eq(accountId)
                .orderBy(PointTransaction::getId, false));
    }

    @Override
    public long countByAccountIdAndReasonSince(Long accountId, String reason, String since) {
        return mapper.selectCountByQuery(QueryWrapper.create()
                .where(PointTransaction::getAccountId).eq(accountId)
                .and(PointTransaction::getReason).eq(reason)
                .and(PointTransaction::getCreatedAt).ge(since));
    }
}
