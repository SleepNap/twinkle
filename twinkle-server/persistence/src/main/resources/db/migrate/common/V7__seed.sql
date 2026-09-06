-- ============================================================
-- Common V7: param_config seed（三方言一致，架构 6.2：migration 管结构、seed 管内容）
-- ============================================================
-- 版本号 7 在所有方言 DDL（V1-V6）之后执行，确保 param_config 表已建。
-- INSERT 只在首次应用时执行（schema_version 版本表保证幂等）。
-- ============================================================
INSERT INTO param_config (config_key, config_value, version) VALUES
    ('game.level.rate', '1.0', 1),
    ('game.exp.rate',  '1.0', 1),
    ('game.mesos.rate', '1.0', 1),
    ('game.drop.rate', '1.0', 1),
    ('world.greetings', '欢迎来到 twinkle', 1);
