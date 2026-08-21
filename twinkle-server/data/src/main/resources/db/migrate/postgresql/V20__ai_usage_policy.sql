-- ============================================================
-- PostgreSQL V20: AI 权限与预算策略（账号维度）
-- ============================================================
-- 策略挂 account_records.id，与 point_account 同口径：同一账号所有 API Key 共享
-- 同一份 AI 预算与模型白名单。不挂 subject_id——所有 key 的 subject_id 都继承签发者
-- （控制台签发出来恒为 subject_owner），挂上去会塌缩成"全体一份"。
--
-- 限额语义沿用 subscription_plan：>0 生效，0 = 不限制。
-- 账号无策略行 = 不限制（只受全局开关约束），保证新表上线不锁死存量账号。
-- ============================================================
CREATE TABLE ai_usage_policy (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    allowed_models TEXT NOT NULL DEFAULT '',
    daily_point_limit BIGINT NOT NULL DEFAULT 0,
    daily_call_limit BIGINT NOT NULL DEFAULT 0,
    daily_token_limit BIGINT NOT NULL DEFAULT 0,
    daily_point_used BIGINT NOT NULL DEFAULT 0,
    daily_call_used BIGINT NOT NULL DEFAULT 0,
    daily_token_used BIGINT NOT NULL DEFAULT 0,
    window_start TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT,
    updated_by TEXT
);
CREATE UNIQUE INDEX idx_ai_usage_policy_account ON ai_usage_policy(account_id);

-- ai_usage_log 自 V4 建表起无任何索引；用量查询按账号 + 时间范围过滤会全表扫。
CREATE INDEX idx_ai_usage_log_account_time ON ai_usage_log(account_id, created_at);
