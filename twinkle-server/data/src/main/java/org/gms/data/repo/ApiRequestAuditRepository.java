package org.gms.data.repo;

import org.gms.data.entity.ApiRequestAudit;

/** 能力面调用审计持久化契约。 */
public interface ApiRequestAuditRepository {

    public void insert(ApiRequestAudit audit);

    public long count();
}
