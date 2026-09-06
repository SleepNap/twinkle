-- ============================================================
-- PostgreSQL V5: bus_outbox_queue / bus_stream_state（消息总线持久化队列）
-- ============================================================
CREATE TABLE bus_outbox_queue (
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
CREATE INDEX idx_bus_outbox_queue_status ON bus_outbox_queue(status);
CREATE TABLE bus_stream_state (
    stream_id VARCHAR(128) PRIMARY KEY,
    last_delivered_seq BIGINT NOT NULL DEFAULT 0
);
