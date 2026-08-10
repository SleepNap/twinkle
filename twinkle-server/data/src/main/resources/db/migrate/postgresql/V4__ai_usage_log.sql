-- ============================================================
-- PostgreSQL V4: ai_usage_log（AI 使用记录，架构 M3-2：计费/观测）
-- ============================================================
CREATE TABLE ai_usage_log (
    id SERIAL PRIMARY KEY,
    tool_name VARCHAR(64) NOT NULL DEFAULT '',
    request_text TEXT NOT NULL DEFAULT '',
    response_length INTEGER NOT NULL DEFAULT 0,
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
