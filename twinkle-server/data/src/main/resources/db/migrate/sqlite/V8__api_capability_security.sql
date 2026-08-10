-- twish 能力面：API-key 生命周期与调用审计。
CREATE TABLE api_key_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    credential_id TEXT NOT NULL,
    key_prefix TEXT NOT NULL,
    secret_hash TEXT NOT NULL,
    subject_id TEXT NOT NULL,
    subject_display_name TEXT NOT NULL,
    created_by_subject_id TEXT NOT NULL,
    server_id TEXT NOT NULL,
    owner_account_id INTEGER,
    display_name TEXT NOT NULL DEFAULT '',
    scopes TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TEXT,
    disabled_at TEXT,
    revoked_at TEXT,
    rotated_from_prefix TEXT,
    last_used_at TEXT,
    permission_version TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_api_key_records_prefix ON api_key_records(key_prefix);
CREATE UNIQUE INDEX idx_api_key_records_credential ON api_key_records(credential_id);

CREATE TABLE api_request_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id TEXT NOT NULL,
    api_key_id INTEGER,
    key_prefix TEXT NOT NULL DEFAULT '',
    method TEXT NOT NULL,
    path TEXT NOT NULL,
    required_scope TEXT NOT NULL DEFAULT '',
    outcome TEXT NOT NULL,
    status_code INTEGER NOT NULL,
    remote_address TEXT NOT NULL DEFAULT '',
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_api_request_audit_key_time ON api_request_audit(api_key_id, created_at);
CREATE INDEX idx_api_request_audit_request_id ON api_request_audit(request_id);

CREATE TABLE tool_execution_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    audit_ref TEXT NOT NULL,
    execution_id TEXT NOT NULL,
    request_id TEXT NOT NULL,
    task_id TEXT,
    step_id TEXT,
    subject_id TEXT NOT NULL,
    credential_id TEXT NOT NULL,
    source TEXT NOT NULL,
    server_id TEXT NOT NULL,
    tool_id TEXT NOT NULL,
    tool_version TEXT NOT NULL,
    required_scopes TEXT NOT NULL,
    authorization_result TEXT NOT NULL,
    policy_version TEXT NOT NULL,
    parameter_summary TEXT NOT NULL,
    result_status TEXT NOT NULL,
    error_code TEXT,
    intent_summary TEXT,
    started_at TEXT NOT NULL,
    completed_at TEXT NOT NULL
);
CREATE UNIQUE INDEX idx_tool_execution_audit_ref ON tool_execution_audit(audit_ref);
CREATE INDEX idx_tool_execution_audit_request ON tool_execution_audit(subject_id, request_id, tool_id);
