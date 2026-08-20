package org.gms.data.repo;

import org.gms.data.entity.AiUsageEntity;

import java.util.List;

/**
 * AI 使用记录仓库（架构 M3-2：计费/观测数据持久化，复用 Dao 设计）。
 */
public interface AiUsageRepository {

    /** 插入一条使用记录。 */
    public void insert(AiUsageEntity usage);

    /** 总调用次数。 */
    public long count();

    /**
     * 按时间区间 + 账号查用量明细（管理面 {@code /admin/v1/ai/usage}）。
     *
     * @param from       起始时间（ISO-8601，含）；null 表示不限
     * @param to         结束时间（ISO-8601，含）；null 表示不限
     * @param accountId  计费账号；null 表示全部账号
     */
    public List<AiUsageEntity> findByRange(String from, String to, Long accountId, int limit);
}
