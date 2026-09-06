-- Web 控制台管理员会话（强鉴权：DB session token，只存 SHA-256 摘要不存明文）。
CREATE TABLE admin_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    token_prefix VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    account_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at VARCHAR(40),
    last_used_at VARCHAR(40),
    revoked_at VARCHAR(40),
    remote_address VARCHAR(128) NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX idx_admin_session_prefix ON admin_session(token_prefix);
CREATE INDEX idx_admin_session_account ON admin_session(account_id, created_at);
