package org.gms.data.repo;

import org.gms.data.entity.AdminOperationAudit;

import java.util.List;

/** 管理操作审计持久化契约（不可抵赖审计）。 */
public interface AdminOperationAuditRepository {

    public void insert(AdminOperationAudit audit);

    public long count();

    /** 按时间倒序读取最近审计；默认实现用于轻量测试替身。 */
    public default List<AdminOperationAudit> findRecent(int limit) {
        return List.of();
    }
}
