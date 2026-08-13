-- ============================================================
-- 积分计费 seed：联网搜索计费成本（配置中心可调，运营在 Web 配置中心改）。
-- ============================================================
-- 每次联网搜索扣多少积分；默认 1 积分/次。BillingService 运行时经 ConfigFacade 读，
-- 运营 upsert 此键即可即时调整（无需重启）。
INSERT INTO param_config (config_key, config_value, version) VALUES
    ('billing.websearch.cost', '1', 1);
