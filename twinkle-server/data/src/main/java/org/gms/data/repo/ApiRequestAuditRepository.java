package org.gms.data.repo;

import org.gms.data.entity.ApiRequestAudit;

import java.util.List;

/** 能力面调用审计持久化契约。 */
public interface ApiRequestAuditRepository {

    public void insert(ApiRequestAudit audit);

    public long count();

    /** 按时间倒序读取最近审计；默认实现用于轻量测试替身。 */
    public default List<ApiRequestAudit> findRecent(int limit) {
        return List.of();
    }
}
