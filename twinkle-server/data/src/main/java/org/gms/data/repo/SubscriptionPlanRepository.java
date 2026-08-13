package org.gms.data.repo;

import org.gms.data.entity.SubscriptionPlan;

import java.util.List;
import java.util.Optional;

/** 订阅计划持久化契约。 */
public interface SubscriptionPlanRepository {

    public Optional<SubscriptionPlan> findById(Long id);

    public Optional<SubscriptionPlan> findByPlanCode(String planCode);

    public List<SubscriptionPlan> findAll();

    public void insert(SubscriptionPlan plan);

    public void update(SubscriptionPlan plan);
}
