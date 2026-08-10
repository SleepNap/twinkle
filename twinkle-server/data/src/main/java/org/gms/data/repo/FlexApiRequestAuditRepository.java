package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.ApiRequestAudit;
import org.gms.data.mapper.ApiRequestAuditMapper;

/** MyBatis-Flex 能力面审计仓储实现。 */
public final class FlexApiRequestAuditRepository implements ApiRequestAuditRepository {

    private final ApiRequestAuditMapper mapper;

    public FlexApiRequestAuditRepository(ApiRequestAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ApiRequestAudit audit) {
        mapper.insertSelective(audit);
    }

    @Override
    public long count() {
        return mapper.selectCountByQuery(QueryWrapper.create());
    }
}
