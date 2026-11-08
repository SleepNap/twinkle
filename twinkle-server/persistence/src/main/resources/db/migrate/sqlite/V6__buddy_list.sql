-- ============================================================
-- SQLite V6: buddy_list（好友关系，架构 4.4 单一属主）
-- ============================================================
-- buddy_list：好友关系（owner_id 视角，双向两行）。status：
--   PENDING 待确认（加好友请求，单一属主 store 的落点）
--   ACCEPTED 已确认（双方列表互见）
-- group_name：owner 视角的分组名，加好友时由申请方选择；v83 整表包按 13 字节字段下发。
-- ============================================================
CREATE TABLE buddy_list (
    owner_id INTEGER NOT NULL,
    buddy_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACCEPTED',
    group_name TEXT NOT NULL DEFAULT 'Default Group',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (owner_id, buddy_id)
);

-- 删除角色时清理其他玩家指向该角色的好友关系；正向查询由主键覆盖。
CREATE INDEX idx_buddy_list_buddy ON buddy_list(buddy_id);
