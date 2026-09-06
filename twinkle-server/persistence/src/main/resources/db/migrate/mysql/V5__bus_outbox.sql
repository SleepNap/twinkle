-- ============================================================
-- MySQL V5: bus_outbox_queue / bus_stream_state（消息总线持久化队列）
-- ============================================================
CREATE TABLE `bus_outbox_queue` (
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
CREATE INDEX `idx_bus_outbox_queue_status` ON `bus_outbox_queue`(`status`);
CREATE TABLE `bus_stream_state` (
    `stream_id` VARCHAR(128) NOT NULL,
    `last_delivered_seq` BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`stream_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
