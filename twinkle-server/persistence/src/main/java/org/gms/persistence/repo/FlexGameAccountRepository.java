package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.persistence.entity.GameAccountRecord;
import org.gms.persistence.mapper.GameAccountRecordMapper;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Flex 实现的账号仓库（M1 登录校验）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不再用 @Singleton 自注册，
 * 避免同一接口出现多个 bean 候选。
 */
public class FlexGameAccountRepository implements GameAccountRepository {

    private final GameAccountRecordMapper mapper;

    public FlexGameAccountRepository(GameAccountRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<GameAccountRecord> findByName(String name) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(GameAccountRecord::getName).eq(name)));
    }

    @Override
    public Optional<GameAccountRecord> findById(Long id) {
        return Optional.ofNullable(mapper.selectOneById(id));
    }

    @Override
    public void insert(GameAccountRecord account) {
        mapper.insertSelective(account);
    }

    @Override
    public void update(GameAccountRecord account) {
        mapper.update(account);
    }

    @Override
    public List<GameAccountRecord> findByNameLike(String query, int limit) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(GameAccountRecord::getName).like(query)
                .limit(limit));
    }

    @Override
    public AccountPage findPage(String query, Boolean banned, int offset, int limit) {
        String normalized = query == null ? "" : query.trim();
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit));
        long total = mapper.selectCountByQuery(pageQuery(normalized, banned));
        List<GameAccountRecord> records = mapper.selectListByQuery(pageQuery(normalized, banned)
                .orderBy(GameAccountRecord::getId).desc()
                .limit(safeOffset, safeLimit));
        return new AccountPage(total, safeOffset, safeLimit, records);
    }

    private static QueryWrapper pageQuery(String query, Boolean banned) {
        QueryWrapper wrapper = QueryWrapper.create()
                .where(GameAccountRecord::getName).like(query);
        if (banned != null) {
            if (banned) {
                wrapper.and(GameAccountRecord::getBanned).eq(1);
            } else {
                wrapper.and(GameAccountRecord::getBanned).ne(1);
            }
        }
        return wrapper;
    }
}
