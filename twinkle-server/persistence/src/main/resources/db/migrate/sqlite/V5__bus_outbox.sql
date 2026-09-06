-- ============================================================
-- SQLite V5: bus_outbox_queue / bus_stream_state（消息总线持久化队列）
-- ============================================================
-- bus_outbox_queue：可靠投递的 outbox。发消息先落此表（PENDING）→ 投递 → DELIVERED → 接收方 ack 后 ACKED。
--   · message_id 全局唯一（接收方幂等去重依据）
--   · stream_id + seq 单一属主序号（同一逻辑流按序投递；接收方按 last_delivered_seq 去重/排序）
--   · 进程崩了重投未 ACKED（架构 4.5：投递成功才 ack，崩了重发）
-- bus_stream_state：每逻辑流已投递序号（接收方幂等去重状态，单一属主序号落点）
-- ============================================================
CREATE TABLE bus_outbox_queue (
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
CREATE INDEX idx_bus_outbox_queue_status ON bus_outbox_queue(status);
CREATE TABLE bus_stream_state (
    stream_id TEXT PRIMARY KEY,
    last_delivered_seq INTEGER NOT NULL DEFAULT 0
);
