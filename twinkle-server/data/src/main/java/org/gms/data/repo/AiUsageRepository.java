package org.gms.data.repo;

import org.gms.data.entity.AiUsageEntity;

/**
 * AI 使用记录仓库（架构 M3-2：计费/观测数据持久化，复用 Dao 设计）。
 */
public interface AiUsageRepository {

    /** 插入一条使用记录。 */
    void insert(AiUsageEntity usage);

    /** 总调用次数。 */
    long count();
}
