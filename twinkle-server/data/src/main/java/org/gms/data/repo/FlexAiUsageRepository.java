package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.AiUsageEntity;
import org.gms.data.mapper.AiUsageMapper;

/**
 * MyBatis-Flex 实现的 AI 使用记录仓库（M3-2 计费落库）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不用 @Singleton 自注册。
 */
public class FlexAiUsageRepository implements AiUsageRepository {

    private final AiUsageMapper mapper;

    public FlexAiUsageRepository(AiUsageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(AiUsageEntity usage) {
        // insertSelective：跳过 null 字段，让 created_at 走 DB DEFAULT（datetime('now')/now()/CURRENT_TIMESTAMP）
        mapper.insertSelective(usage);
    }

    @Override
    public long count() {
        return mapper.selectCountByQuery(QueryWrapper.create());
    }
}
