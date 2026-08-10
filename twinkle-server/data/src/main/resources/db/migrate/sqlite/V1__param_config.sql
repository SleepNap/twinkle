-- ============================================================
-- SQLite V1: param_config 配置表（架构 4.6.5 配置中心真值）
-- ============================================================
-- param_config：L1 配置真值，配置变更走"写 DB → version +1 → EventBus 广播"。
-- * 自带 version 字段（支持单调递增 + 校对）
-- * 键采用点命名空间（game.level.rate 等）
-- * updated_at 跨方言兼容（三库统一存 ISO 8601 TEXT，避免 JDBC 时间类型转换差异）
-- ============================================================
CREATE TABLE param_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key TEXT NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
