-- Web 控制台不可抵赖审计：GM 写操作记录操作者、原因、变更前后摘要。
CREATE TABLE admin_operation_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id TEXT NOT NULL,
    account_id INTEGER,
    account_name TEXT NOT NULL DEFAULT '',
    method TEXT NOT NULL,
    path TEXT NOT NULL,
    operation TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    before_summary TEXT NOT NULL DEFAULT '',
    after_summary TEXT NOT NULL DEFAULT '',
    result_status TEXT NOT NULL,
    status_code INTEGER NOT NULL,
    remote_address TEXT NOT NULL DEFAULT '',
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_admin_operation_audit_account ON admin_operation_audit(account_id, created_at);
CREATE INDEX idx_admin_operation_audit_request ON admin_operation_audit(request_id);
