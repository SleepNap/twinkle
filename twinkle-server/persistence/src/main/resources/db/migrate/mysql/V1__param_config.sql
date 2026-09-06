-- ============================================================
-- MySQL V1: param_config 配置表（架构 4.6.5 配置中心真值）
-- ============================================================
CREATE TABLE `param_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `config_key` VARCHAR(255) NOT NULL UNIQUE,
    `config_value` TEXT NOT NULL,
    `version` BIGINT NOT NULL DEFAULT 0,
    `updated_at` TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
