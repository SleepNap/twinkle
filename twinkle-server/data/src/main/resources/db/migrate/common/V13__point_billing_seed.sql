-- ============================================================
-- 积分计费 seed：模型倍率与订阅计划初始值（三方言一致，放 common）。
-- ============================================================
-- 倍率放大 1e4 存整数：local-rule 本地规则零成本（rate 0）；deepseek-chat 示例倍率
--（每 1 万 token 计 10000 积分，即 1 token = 1 积分，实现时按 1e4 缩放）。
INSERT INTO model_rate (model_key, input_rate, output_rate, enabled) VALUES
    ('local-rule', 0, 0, 1),
    ('deepseek-chat', 10000, 10000, 1);

-- free 三档限额为 0 = 该窗口不限制（纯余额扣）；basic/pro 为示例限额。
INSERT INTO subscription_plan (plan_code, display_name, monthly_limit, weekly_limit, five_hour_limit, price_nx, enabled) VALUES
    ('free', '免费', 0, 0, 0, 0, 1),
    ('basic', '基础', 100000, 30000, 5000, 1000, 1),
    ('pro', '专业', 500000, 150000, 25000, 5000, 1);
