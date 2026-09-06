-- ============================================================
-- Web 控制台 RBAC seed：内置管理员角色（三方言一致，放 common）。
-- ============================================================
-- super_admin=全部权限；operator=日常运维；auditor=只读。permissions 逗号分隔，* 为通配。
INSERT INTO admin_role (role_code, display_name, description, permissions) VALUES
    ('super_admin', '超级管理员', '拥有全部管理权限', '*'),
    ('operator', '运维', '日常运维操作（配置/踢人/抓包/重载/重启/任务）', 'admin:read,admin.config:write,admin.player:kick,admin.packet:trace,admin.reload:logic,admin.reload:scripts,admin.reload:wz,admin.restart,admin.task:manage'),
    ('auditor', '审计员', '只读查看', 'admin:read');
