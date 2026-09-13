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

-- 删除角色时清理其他玩家指向该角色的好友关系；正向查询由主键覆盖。
CREATE INDEX idx_buddy_list_buddy ON buddy_list(buddy_id);
