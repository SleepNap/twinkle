-- ============================================================
-- V1: 初始表结构（架构 4.6.5 配置中心 / 6.2 数据层）
-- ============================================================
-- 设计动机：param_conf 是 L1 配置真值，配置变更走"写 DB → version +1 → EventBus 广播"。
-- 架构思路参考自 BeiDou-Server 的配置表设计，但表结构按 twinkle 自身需求定制：
-- * 自带 version 字段（支持单调递增 + 校对）
-- * 键采用点命名空间（game.level.rate 等）
-- * updated_at 跨方言兼容（三库统一存 ISO 8601 TEXT，避免 JDBC 时间类型转换差异）
-- ============================================================

-- param_conf: 配置参数
-- 方言节标记：-- dialect:sqlite / -- dialect:postgresql / -- dialect:mysql
-- 与 DbDialect.DialectId 枚举 name() 小写保持一致（见 DataSourceFactory）
-- 每个节以空行结束（见 MigrationRunner.filterForDialect 语义）

-- dialect:sqlite
CREATE TABLE param_conf (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    config_key TEXT NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- dialect:postgresql
CREATE TABLE param_conf (
    id BIGSERIAL PRIMARY KEY,
    config_key TEXT NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    -- updated_at 统一存 ISO 8601 TEXT：三库行为一致，避免 JDBC 时间类型转换差异
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- dialect:mysql
CREATE TABLE param_conf (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(255) NOT NULL UNIQUE,
    config_value TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 初始数据 seed（架构 6.2：seed 随版本发布，migration 管结构、seed 管内容）
-- INSERT 只在首次应用时执行（版本表保证幂等）
-- ============================================================
INSERT INTO param_conf (config_key, config_value, version) VALUES
    ('game.level.rate', '1.0', 1),
    ('game.exp.rate',  '1.0', 1),
    ('game.mesos.rate', '1.0', 1),
    ('game.drop.rate', '1.0', 1),
    ('world.greetings', '欢迎来到 twinkle', 1);
