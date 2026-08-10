-- twish 能力面：API-key 生命周期与调用审计。
CREATE TABLE api_key_records (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    credential_id VARCHAR(64) NOT NULL,
    key_prefix VARCHAR(32) NOT NULL,
    secret_hash VARCHAR(64) NOT NULL,
    subject_id VARCHAR(128) NOT NULL,
    subject_display_name VARCHAR(128) NOT NULL,
    created_by_subject_id VARCHAR(128) NOT NULL,
    server_id VARCHAR(128) NOT NULL,
    owner_account_id BIGINT,
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    scopes TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at VARCHAR(40),
    disabled_at VARCHAR(40),
    revoked_at VARCHAR(40),
    rotated_from_prefix VARCHAR(32),
    last_used_at VARCHAR(40),
    permission_version VARCHAR(64) NOT NULL
);
CREATE UNIQUE INDEX idx_api_key_records_prefix ON api_key_records(key_prefix);
CREATE UNIQUE INDEX idx_api_key_records_credential ON api_key_records(credential_id);

CREATE TABLE api_request_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(64) NOT NULL,
    api_key_id BIGINT,
    key_prefix VARCHAR(32) NOT NULL DEFAULT '',
    method VARCHAR(16) NOT NULL,
    path VARCHAR(512) NOT NULL,
    required_scope VARCHAR(64) NOT NULL DEFAULT '',
    outcome VARCHAR(32) NOT NULL,
    status_code INTEGER NOT NULL,
    remote_address VARCHAR(128) NOT NULL DEFAULT '',
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_api_request_audit_key_time ON api_request_audit(api_key_id, created_at);
CREATE INDEX idx_api_request_audit_request_id ON api_request_audit(request_id);

CREATE TABLE tool_execution_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    audit_ref VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(128),
    step_id VARCHAR(128),
    subject_id VARCHAR(128) NOT NULL,
    credential_id VARCHAR(64) NOT NULL,
    source VARCHAR(32) NOT NULL,
    server_id VARCHAR(128) NOT NULL,
    tool_id VARCHAR(128) NOT NULL,
    tool_version VARCHAR(32) NOT NULL,
    required_scopes TEXT NOT NULL,
    authorization_result VARCHAR(32) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    parameter_summary TEXT NOT NULL,
    result_status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    intent_summary VARCHAR(512),
    started_at VARCHAR(40) NOT NULL,
    completed_at VARCHAR(40) NOT NULL
);
CREATE UNIQUE INDEX idx_tool_execution_audit_ref ON tool_execution_audit(audit_ref);
CREATE INDEX idx_tool_execution_audit_request ON tool_execution_audit(subject_id, request_id, tool_id);
