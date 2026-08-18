-- Web 控制台 RBAC：管理员角色与账号-角色关联（强鉴权 + RBAC 安全里程碑）。
CREATE TABLE admin_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    description VARCHAR(512) NOT NULL DEFAULT '',
    permissions TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at VARCHAR(40)
);
CREATE UNIQUE INDEX idx_admin_role_code ON admin_role(role_code);

CREATE TABLE account_admin_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);
CREATE UNIQUE INDEX idx_account_admin_role_account_role ON account_admin_role(account_id, role_id);
