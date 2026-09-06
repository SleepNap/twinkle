-- ============================================================
-- SQLite V12: 积分服务（账号层积分账户 + 订阅计划 + 积分流水）
-- ============================================================
-- 积分是账号维度资源（同一账号所有 API Key 共享）。
-- ============================================================
CREATE TABLE point_account (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    balance INTEGER NOT NULL DEFAULT 0,
    plan_id INTEGER,
    monthly_used INTEGER NOT NULL DEFAULT 0,
    weekly_used INTEGER NOT NULL DEFAULT 0,
    five_hour_used INTEGER NOT NULL DEFAULT 0,
    monthly_window_start TEXT,
    weekly_window_start TEXT,
    five_hour_window_start TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT
);
CREATE UNIQUE INDEX idx_point_account_account ON point_account(account_id);

CREATE TABLE subscription_plan (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    plan_code TEXT NOT NULL,
    display_name TEXT NOT NULL DEFAULT '',
    monthly_limit INTEGER NOT NULL DEFAULT 0,
    weekly_limit INTEGER NOT NULL DEFAULT 0,
    five_hour_limit INTEGER NOT NULL DEFAULT 0,
    price_nx INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX idx_subscription_plan_code ON subscription_plan(plan_code);

CREATE TABLE point_transaction (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id INTEGER NOT NULL,
    change_amount INTEGER NOT NULL DEFAULT 0,
    balance_after INTEGER NOT NULL DEFAULT 0,
    reason TEXT NOT NULL DEFAULT '',
    reference_id TEXT,
    detail TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_point_transaction_account ON point_transaction(account_id, created_at);
