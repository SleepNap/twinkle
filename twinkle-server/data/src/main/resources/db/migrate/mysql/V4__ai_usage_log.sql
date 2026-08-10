-- ============================================================
-- MySQL V4: ai_usage_log（AI 使用记录，架构 M3-2：计费/观测）
-- ============================================================
CREATE TABLE `ai_usage_log` (
    `id` INT(11) NOT NULL AUTO_INCREMENT,
    `tool_name` VARCHAR(64) NOT NULL DEFAULT '',
    `request_text` TEXT NOT NULL,
    `response_length` INT(11) NOT NULL DEFAULT 0,
    `elapsed_ms` INT(11) NOT NULL DEFAULT 0,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
