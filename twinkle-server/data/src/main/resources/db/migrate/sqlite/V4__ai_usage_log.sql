-- ============================================================
-- SQLite V4: ai_usage_log（AI 使用记录，架构 M3-2：计费/观测）
-- ============================================================
-- 每次 AI 请求一行（工具名/耗时/字符数），计费与观测用。
-- ============================================================
CREATE TABLE ai_usage_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tool_name TEXT NOT NULL DEFAULT '',
    request_text TEXT NOT NULL DEFAULT '',
    response_length INTEGER NOT NULL DEFAULT 0,
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
