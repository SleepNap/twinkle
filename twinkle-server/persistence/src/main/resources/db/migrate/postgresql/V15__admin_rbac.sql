-- Web 控制台 RBAC：管理员角色与账号-角色关联（强鉴权 + RBAC 安全里程碑）。
CREATE TABLE admin_role (
    id BIGSERIAL PRIMARY KEY,
    role_code TEXT NOT NULL,
    display_name TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    permissions TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT
);
CREATE UNIQUE INDEX idx_admin_role_code ON admin_role(role_code);

CREATE TABLE account_admin_role (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);
CREATE UNIQUE INDEX idx_account_admin_role_account_role ON account_admin_role(account_id, role_id);
