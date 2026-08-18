package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.AdminOperationAudit;
import org.gms.data.mapper.AdminOperationAuditMapper;

import java.util.List;

/** MyBatis-Flex 管理操作审计仓储实现。 */
public final class FlexAdminOperationAuditRepository implements AdminOperationAuditRepository {

    private final AdminOperationAuditMapper mapper;

    public FlexAdminOperationAuditRepository(AdminOperationAuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(AdminOperationAudit audit) {
        mapper.insertSelective(audit);
    }

    @Override
    public long count() {
        return mapper.selectCountByQuery(QueryWrapper.create());
    }

    @Override
    public List<AdminOperationAudit> findRecent(int limit) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .orderBy(AdminOperationAudit::getId, false)
                .limit(limit));
    }
}
