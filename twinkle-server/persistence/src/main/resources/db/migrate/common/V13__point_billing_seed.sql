-- ============================================================
-- 积分服务 seed：订阅计划初始值（三方言一致，放 common）。
-- ============================================================
-- free 三档限额为 0 = 该窗口不限制（纯余额扣）；basic/pro 为示例限额。
INSERT INTO subscription_plan (plan_code, display_name, monthly_limit, weekly_limit, five_hour_limit, price_nx, enabled) VALUES
    ('free', '免费', 0, 0, 0, 0, 1),
    ('basic', '基础', 100000, 30000, 5000, 1000, 1),
    ('pro', '专业', 500000, 150000, 25000, 5000, 1);
