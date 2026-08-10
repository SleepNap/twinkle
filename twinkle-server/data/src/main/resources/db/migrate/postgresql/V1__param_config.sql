-- ============================================================
-- PostgreSQL V1: param_config 配置表（架构 4.6.5 配置中心真值）
-- ============================================================
CREATE TABLE param_config (
    id BIGSERIAL PRIMARY KEY,
    config_key TEXT NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
