-- ============================================================
-- PostgreSQL V6: buddy_list（好友关系，架构 4.4 单一属主）
-- ============================================================
CREATE TABLE buddy_list (
    owner_id BIGINT NOT NULL,
    buddy_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, buddy_id)
);
