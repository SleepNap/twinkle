-- ============================================================
-- V6: 好友列表（架构 4.4 单一属主：好友关系真值持久化，V4 已有 ai_usage）
-- ============================================================
-- 新表（非 newmaple 既有表，扩展走 migration 新表，红线 2 兼容）。
-- buddylist：好友关系（owner_id 视角，双向两行）。status：
--   PENDING 待确认（加好友请求，单一属主 store 的落点）
--   ACCEPTED 已确认（双方列表互见）
-- 方言节：-- dialect:sqlite / -- dialect:postgresql / -- dialect:mysql
-- 注意：节内语句之间【不要用空行】——空行 = 节结束回全方言。
-- ============================================================

-- dialect:sqlite
CREATE TABLE buddylist (
    owner_id INTEGER NOT NULL,
    buddy_id INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACCEPTED',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (owner_id, buddy_id)
);
-- dialect:postgresql
CREATE TABLE buddylist (
    owner_id BIGINT NOT NULL,
    buddy_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, buddy_id)
);
-- dialect:mysql
CREATE TABLE `buddylist` (
    `owner_id` BIGINT NOT NULL,
    `buddy_id` BIGINT NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`owner_id`, `buddy_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
