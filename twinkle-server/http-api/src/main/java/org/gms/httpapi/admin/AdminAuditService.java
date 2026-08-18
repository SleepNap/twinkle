package org.gms.httpapi.admin;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.AdminOperationAudit;
import org.gms.data.repo.AdminOperationAuditRepository;
import org.gms.i18n.I18n;

/** 管理操作不可抵赖审计。写失败不阻断业务响应，但记录错误日志供运维修复。 */
@Log4j2
public final class AdminAuditService {

    private final AdminOperationAuditRepository repository;

    public AdminAuditService(AdminOperationAuditRepository repository) {
        this.repository = repository;
    }

    public void record(String requestId, AdminPrincipal principal, String method, String path,
                       String operation, String reason, String beforeSummary, String afterSummary,
                       String resultStatus, int statusCode, String remoteAddress, long elapsedMs) {
        AdminOperationAudit audit = new AdminOperationAudit();
        audit.setRequestId(requestId);
        if (principal != null) {
            audit.setAccountId(principal.accountId());
            audit.setAccountName(principal.accountName());
        }
        audit.setMethod(method);
        audit.setPath(path);
        audit.setOperation(safe(operation, 64));
        audit.setReason(safe(reason, 256));
        audit.setBeforeSummary(safe(beforeSummary, 512));
        audit.setAfterSummary(safe(afterSummary, 512));
        audit.setResultStatus(resultStatus);
        audit.setStatusCode(statusCode);
        audit.setRemoteAddress(safe(remoteAddress, 128));
        audit.setElapsedMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, elapsedMs)));
        try {
            repository.insert(audit);
        } catch (RuntimeException e) {
            log.error(I18n.message("log.admin.audit_write_failed"), requestId, e);
        }
    }

    private static String safe(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
