package org.gms.data.repo;

import org.gms.data.entity.PointAccount;

import java.util.List;
import java.util.Optional;

/** 积分账户持久化契约。 */
public interface PointAccountRepository {

    public Optional<PointAccount> findByAccountId(Long accountId);

    public void insert(PointAccount account);

    public void update(PointAccount account);

    public List<PointAccount> findAll();
}
