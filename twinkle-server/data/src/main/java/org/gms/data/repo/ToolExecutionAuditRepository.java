package org.gms.data.repo;

import org.gms.data.entity.ToolExecutionAudit;

import java.util.Optional;
import java.util.List;

/** Tool 权威审计持久化契约。 */
public interface ToolExecutionAuditRepository {

    public void insert(ToolExecutionAudit audit);

    public Optional<ToolExecutionAudit> findByAuditRef(String auditRef);

    public long count();

    /** 按时间倒序读取最近审计；默认实现用于轻量测试替身。 */
    public default List<ToolExecutionAudit> findRecent(int limit) {
        return List.of();
    }
}
