-- Web 控制台管理员会话（强鉴权：DB session token，只存 SHA-256 摘要不存明文）。
CREATE TABLE admin_session (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    token_prefix TEXT NOT NULL,
    token_hash TEXT NOT NULL,
    account_id INTEGER NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TEXT,
    last_used_at TEXT,
    revoked_at TEXT,
    remote_address TEXT NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX idx_admin_session_prefix ON admin_session(token_prefix);
CREATE INDEX idx_admin_session_account ON admin_session(account_id, created_at);
