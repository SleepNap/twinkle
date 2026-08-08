-- ============================================================
-- V5: 消息总线持久化队列（架构 4.5 可靠性三件套：持久化队列 + 幂等去重 + 单一属主序号）
-- ============================================================
-- 新表（非 newmaple 既有表，扩展走 migration 新表，红线 2 兼容）。
-- bus_outbox：可靠投递的 outbox。发消息先落此表（PENDING）→ 投递 → DELIVERED → 接收方 ack 后 ACKED。
--   · message_id 全局唯一（接收方幂等去重依据）
--   · stream_id + seq 单一属主序号（同一逻辑流按序投递；接收方按 last_delivered_seq 去重/排序）
--   · 进程崩了重投未 ACKED（架构 4.5：投递成功才 ack，崩了重发）
-- bus_stream：每逻辑流已投递序号（接收方幂等去重状态，单一属主序号落点）
-- 方言节：-- dialect:sqlite / -- dialect:postgresql / -- dialect:mysql
-- 注意：节内语句之间【不要用空行】——空行 = 节结束回全方言。
-- ============================================================

-- dialect:sqlite
CREATE TABLE bus_outbox (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stream_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    message_id TEXT NOT NULL UNIQUE,
    target TEXT NOT NULL,
    payload_type TEXT NOT NULL,
    payload TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    delivered_at TEXT
);
CREATE INDEX idx_bus_outbox_status ON bus_outbox(status);
CREATE TABLE bus_stream (
    stream_id TEXT PRIMARY KEY,
    last_delivered_seq INTEGER NOT NULL DEFAULT 0
);
-- dialect:postgresql
CREATE TABLE bus_outbox (
    id SERIAL PRIMARY KEY,
    stream_id VARCHAR(128) NOT NULL,
    seq BIGINT NOT NULL,
    message_id VARCHAR(128) NOT NULL UNIQUE,
    target VARCHAR(256) NOT NULL,
    payload_type VARCHAR(256) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ
);
CREATE INDEX idx_bus_outbox_status ON bus_outbox(status);
CREATE TABLE bus_stream (
    stream_id VARCHAR(128) PRIMARY KEY,
    last_delivered_seq BIGINT NOT NULL DEFAULT 0
);
-- dialect:mysql
CREATE TABLE `bus_outbox` (
    `id` INT(11) NOT NULL AUTO_INCREMENT,
    `stream_id` VARCHAR(128) NOT NULL,
    `seq` BIGINT NOT NULL,
    `message_id` VARCHAR(128) NOT NULL UNIQUE,
    `target` VARCHAR(256) NOT NULL,
    `payload_type` VARCHAR(256) NOT NULL,
    `payload` TEXT NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `delivered_at` TIMESTAMP NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX `idx_bus_outbox_status` ON `bus_outbox`(`status`);
CREATE TABLE `bus_stream` (
    `stream_id` VARCHAR(128) NOT NULL,
    `last_delivered_seq` BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`stream_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
