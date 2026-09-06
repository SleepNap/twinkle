-- ============================================================
-- MySQL V12: 积分服务（账号层积分账户 + 订阅计划 + 积分流水）
-- ============================================================
-- 积分是账号维度资源（同一账号所有 API Key 共享）。
-- ============================================================
CREATE TABLE point_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    balance BIGINT NOT NULL DEFAULT 0,
    plan_id BIGINT,
    monthly_used BIGINT NOT NULL DEFAULT 0,
    weekly_used BIGINT NOT NULL DEFAULT 0,
    five_hour_used BIGINT NOT NULL DEFAULT 0,
    monthly_window_start VARCHAR(40),
    weekly_window_start VARCHAR(40),
    five_hour_window_start VARCHAR(40),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at VARCHAR(40)
);
CREATE UNIQUE INDEX idx_point_account_account ON point_account(account_id);

CREATE TABLE subscription_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    plan_code VARCHAR(64) NOT NULL,
    display_name VARCHAR(128) NOT NULL DEFAULT '',
    monthly_limit BIGINT NOT NULL DEFAULT 0,
    weekly_limit BIGINT NOT NULL DEFAULT 0,
    five_hour_limit BIGINT NOT NULL DEFAULT 0,
    price_nx INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX idx_subscription_plan_code ON subscription_plan(plan_code);

CREATE TABLE point_transaction (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    account_id BIGINT NOT NULL,
    change_amount BIGINT NOT NULL DEFAULT 0,
    balance_after BIGINT NOT NULL DEFAULT 0,
    reason VARCHAR(32) NOT NULL DEFAULT '',
    reference_id VARCHAR(128),
    detail TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_point_transaction_account ON point_transaction(account_id, created_at);
