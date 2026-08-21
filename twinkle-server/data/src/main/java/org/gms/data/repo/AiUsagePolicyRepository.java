package org.gms.data.repo;

import org.gms.data.entity.AiUsagePolicy;

import java.util.List;
import java.util.Optional;

/** AI 权限与预算策略持久化契约（账号维度）。 */
public interface AiUsagePolicyRepository {

    public Optional<AiUsagePolicy> findByAccountId(Long accountId);

    public List<AiUsagePolicy> findAll();

    public void insert(AiUsagePolicy policy);

    public void update(AiUsagePolicy policy);

    /** 原子累加日用量；返回受影响行数，0 表示该账号无策略行（不限制，无需计数）。 */
    public int addDailyUsage(Long accountId, long points, long calls, long tokens, String updatedAt);

    /** 条件重置日窗口（仅当窗口起点仍为 oldWindowStart）；返回受影响行数。 */
    public int resetDailyWindow(Long accountId, String oldWindowStart, String newWindowStart);
}
