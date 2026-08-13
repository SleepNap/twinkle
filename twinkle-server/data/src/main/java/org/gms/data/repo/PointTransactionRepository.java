package org.gms.data.repo;

import org.gms.data.entity.PointTransaction;

import java.util.List;

/** 积分流水持久化契约。 */
public interface PointTransactionRepository {

    public void insert(PointTransaction transaction);

    public List<PointTransaction> findByAccountId(Long accountId);

    /** 统计某账号某 reason 自 since 起的记录数（用于每日签到/金币购买的每日限）。 */
    public long countByAccountIdAndReasonSince(Long accountId, String reason, String since);
}
