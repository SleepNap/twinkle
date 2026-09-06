-- Web 控制台不可抵赖审计：GM 写操作记录操作者、原因、变更前后摘要。
CREATE TABLE admin_operation_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    account_id BIGINT,
    account_name VARCHAR(128) NOT NULL DEFAULT '',
    method VARCHAR(16) NOT NULL,
    path VARCHAR(512) NOT NULL,
    operation VARCHAR(64) NOT NULL DEFAULT '',
    reason VARCHAR(256) NOT NULL DEFAULT '',
    before_summary TEXT NOT NULL,
    after_summary TEXT NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    status_code INTEGER NOT NULL,
    remote_address VARCHAR(128) NOT NULL DEFAULT '',
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_admin_operation_audit_account ON admin_operation_audit(account_id, created_at);
CREATE INDEX idx_admin_operation_audit_request ON admin_operation_audit(request_id);
