-- ============================================================
-- SQLite V6: buddy_list（好友关系，架构 4.4 单一属主）
-- ============================================================
-- buddy_list：好友关系（owner_id 视角，双向两行）。status：
--   PENDING 待确认（加好友请求，单一属主 store 的落点）
--   ACCEPTED 已确认（双方列表互见）
-- ============================================================
CREATE TABLE buddy_list (
    owner_id INTEGER NOT NULL,
    buddy_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACCEPTED',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (owner_id, buddy_id)
);
