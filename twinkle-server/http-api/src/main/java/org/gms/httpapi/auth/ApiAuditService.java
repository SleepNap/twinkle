package org.gms.httpapi.auth;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.ApiRequestAudit;
import org.gms.data.repo.ApiRequestAuditRepository;
import org.gms.i18n.I18n;

/** 审计写入失败不改变业务响应，但会写错误日志供运维修复。 */
@Log4j2
public final class ApiAuditService {

    private final ApiRequestAuditRepository repository;

    public ApiAuditService(ApiRequestAuditRepository repository) {
        this.repository = repository;
    }

    public void record(String requestId, ApiPrincipal principal, String method, String path,
                       String requiredScope, String outcome, int statusCode,
                       String remoteAddress, long elapsedMs) {
        ApiRequestAudit audit = new ApiRequestAudit();
        audit.setRequestId(requestId);
        if (principal != null) {
            audit.setApiKeyId(principal.keyId());
            audit.setKeyPrefix(principal.keyPrefix());
        }
        audit.setMethod(method);
        audit.setPath(path);
        audit.setRequiredScope(requiredScope == null ? "" : requiredScope);
        audit.setOutcome(outcome);
        audit.setStatusCode(statusCode);
        audit.setRemoteAddress(remoteAddress);
        audit.setElapsedMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, elapsedMs)));
        try {
            repository.insert(audit);
        } catch (RuntimeException e) {
            log.error(I18n.message("log.audit.write_failed"), requestId, e);
        }
    }
}
