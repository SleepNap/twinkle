package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.Account;
import org.gms.data.mapper.AccountMapper;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Flex 实现的账号仓库（M1 登录校验）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不再用 @Singleton 自注册，
 * 避免同一接口出现多个 bean 候选。
 */
public class FlexAccountRepository implements AccountRepository {

    private final AccountMapper mapper;

    public FlexAccountRepository(AccountMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Account> findByName(String name) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(Account::getName).eq(name)));
    }

    @Override
    public Optional<Account> findById(Long id) {
        return Optional.ofNullable(mapper.selectOneById(id));
    }

    @Override
    public void insert(Account account) {
        mapper.insertSelective(account);
    }

    @Override
    public void update(Account account) {
        mapper.update(account);
    }

    @Override
    public List<Account> findByNameLike(String query, int limit) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(Account::getName).like(query)
                .limit(limit));
    }

    @Override
    public AccountPage findPage(String query, Boolean banned, int offset, int limit) {
        String normalized = query == null ? "" : query.trim();
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit));
        long total = mapper.selectCountByQuery(pageQuery(normalized, banned));
        List<Account> records = mapper.selectListByQuery(pageQuery(normalized, banned)
                .orderBy(Account::getId).desc()
                .limit(safeOffset, safeLimit));
        return new AccountPage(total, safeOffset, safeLimit, records);
    }

    private static QueryWrapper pageQuery(String query, Boolean banned) {
        QueryWrapper wrapper = QueryWrapper.create()
                .where(Account::getName).like(query);
        if (banned != null) {
            if (banned) {
                wrapper.and(Account::getBanned).eq(1);
            } else {
                wrapper.and(Account::getBanned).ne(1);
            }
        }
        return wrapper;
    }
}
