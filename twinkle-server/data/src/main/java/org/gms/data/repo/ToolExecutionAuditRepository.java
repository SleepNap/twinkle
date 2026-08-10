package org.gms.data.repo;

import org.gms.data.entity.ToolExecutionAudit;

import java.util.Optional;

/** Tool 权威审计持久化契约。 */
public interface ToolExecutionAuditRepository {

    public void insert(ToolExecutionAudit audit);

    public Optional<ToolExecutionAudit> findByAuditRef(String auditRef);

    public long count();
}
