-- ============================================================
-- V4: AI 使用记录（架构 M3-2：计费/记忆/配置落 SQLite，复用 Dao 设计）
-- ============================================================
-- 新表（非 newmaple 既有表，扩展走 migration 新表，红线 2 兼容）。
-- ai_usage：每次 AI 请求一行（工具名/耗时/字符数），计费与观测用。
-- 方言节：-- dialect:sqlite / -- dialect:postgresql / -- dialect:mysql
-- 注意：节内语句之间【不要用空行】——空行 = 节结束回全方言。
-- ============================================================

-- dialect:sqlite
CREATE TABLE ai_usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_name TEXT NOT NULL DEFAULT '',
    request_text TEXT NOT NULL DEFAULT '',
    response_length INTEGER NOT NULL DEFAULT 0,
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
-- dialect:postgresql
CREATE TABLE ai_usage (
    id SERIAL PRIMARY KEY,
    tool_name VARCHAR(64) NOT NULL DEFAULT '',
    request_text TEXT NOT NULL DEFAULT '',
    response_length INTEGER NOT NULL DEFAULT 0,
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- dialect:mysql
CREATE TABLE `ai_usage` (
    `id` INT(11) NOT NULL AUTO_INCREMENT,
    `tool_name` VARCHAR(64) NOT NULL DEFAULT '',
    `request_text` TEXT NOT NULL,
    `response_length` INT(11) NOT NULL DEFAULT 0,
    `elapsed_ms` INT(11) NOT NULL DEFAULT 0,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
