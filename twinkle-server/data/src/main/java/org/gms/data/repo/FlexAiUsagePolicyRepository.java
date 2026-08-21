package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.AiUsagePolicy;
import org.gms.data.mapper.AiUsagePolicyMapper;

import java.util.List;
import java.util.Optional;

/** MyBatis-Flex AI 策略仓储实现。 */
public final class FlexAiUsagePolicyRepository implements AiUsagePolicyRepository {

    private final AiUsagePolicyMapper mapper;

    public FlexAiUsagePolicyRepository(AiUsagePolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AiUsagePolicy> findByAccountId(Long accountId) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(AiUsagePolicy::getAccountId).eq(accountId)));
    }

    @Override
    public List<AiUsagePolicy> findAll() {
        return mapper.selectListByQuery(QueryWrapper.create().orderBy(AiUsagePolicy::getId, true));
    }

    @Override
    public void insert(AiUsagePolicy policy) {
        mapper.insertSelective(policy);
    }

    @Override
    public void update(AiUsagePolicy policy) {
        mapper.update(policy);
    }

    @Override
    public int addDailyUsage(Long accountId, long points, long calls, long tokens, String updatedAt) {
        return mapper.addDailyUsage(accountId, points, calls, tokens, updatedAt);
    }

    @Override
    public int resetDailyWindow(Long accountId, String oldWindowStart, String newWindowStart) {
        return mapper.resetDailyWindow(accountId, oldWindowStart, newWindowStart);
    }
}
