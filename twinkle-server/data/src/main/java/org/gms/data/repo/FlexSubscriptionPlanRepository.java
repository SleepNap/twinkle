package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.SubscriptionPlan;
import org.gms.data.mapper.SubscriptionPlanMapper;

import java.util.List;
import java.util.Optional;

/** MyBatis-Flex 订阅计划仓储实现。 */
public final class FlexSubscriptionPlanRepository implements SubscriptionPlanRepository {

    private final SubscriptionPlanMapper mapper;

    public FlexSubscriptionPlanRepository(SubscriptionPlanMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SubscriptionPlan> findById(Long id) {
        return Optional.ofNullable(mapper.selectOneById(id));
    }

    @Override
    public Optional<SubscriptionPlan> findByPlanCode(String planCode) {
        return Optional.ofNullable(mapper.selectOneByQuery(
                QueryWrapper.create().where(SubscriptionPlan::getPlanCode).eq(planCode)));
    }

    @Override
    public List<SubscriptionPlan> findAll() {
        return mapper.selectListByQuery(QueryWrapper.create().orderBy(SubscriptionPlan::getId, true));
    }

    @Override
    public void insert(SubscriptionPlan plan) {
        mapper.insertSelective(plan);
    }

    @Override
    public void update(SubscriptionPlan plan) {
        mapper.update(plan);
    }
}
