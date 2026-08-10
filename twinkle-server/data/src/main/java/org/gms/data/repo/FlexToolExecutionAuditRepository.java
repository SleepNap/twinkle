package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.ToolExecutionAudit;
import org.gms.data.mapper.ToolExecutionAuditMapper;

import java.util.Optional;

/** MyBatis-Flex Tool 权威审计仓储。 */
public final class FlexToolExecutionAuditRepository implements ToolExecutionAuditRepository {

    private final ToolExecutionAuditMapper mapper;

    public FlexToolExecutionAuditRepository(ToolExecutionAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(ToolExecutionAudit audit) {
        mapper.insertSelective(audit);
    }

    @Override
    public Optional<ToolExecutionAudit> findByAuditRef(String auditRef) {
        return Optional.ofNullable(mapper.selectOneByQuery(QueryWrapper.create()
                .where(ToolExecutionAudit::getAuditRef).eq(auditRef)));
    }

    @Override
    public long count() {
        return mapper.selectCountByQuery(QueryWrapper.create());
    }
}
