-- ============================================================
-- MySQL V3: quest_status / quest_progress / inventory_items（任务/背包存档）
-- ============================================================
-- quest_status 九列一次建全（原五列 + expires/forfeited/completed/info 四列）。
-- ============================================================
CREATE TABLE `quest_status` (
    `quest_status_id` INT(11) NOT NULL AUTO_INCREMENT,
    `character_id` INT(11) NOT NULL DEFAULT 0,
    `quest` INT(11) NOT NULL DEFAULT 0,
    `status` INT(11) NOT NULL DEFAULT 0,
    `time` INT(11) NOT NULL DEFAULT 0,
    `expires` BIGINT(20) NOT NULL DEFAULT 0,
    `forfeited` INT(11) NOT NULL DEFAULT 0,
    `completed` INT(11) NOT NULL DEFAULT 0,
    `info` TINYINT(3) NOT NULL DEFAULT 0,
    PRIMARY KEY (`quest_status_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE `quest_progress` (
    `id` INT(11) NOT NULL AUTO_INCREMENT,
    `character_id` INT(11) NOT NULL DEFAULT 0,
    `quest_status_id` INT(11) NOT NULL DEFAULT 0,
    `progress_id` INT(11) NOT NULL DEFAULT 0,
    `progress` VARCHAR(15) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE `inventory_items` (
    `inventory_item_id` INT(11) NOT NULL AUTO_INCREMENT,
    `type` TINYINT(4) NOT NULL DEFAULT 0,
    `character_id` INT(11) NOT NULL DEFAULT 0,
    `account_id` INT(11) NOT NULL DEFAULT 0,
    `item_id` INT(11) NOT NULL DEFAULT 0,
    `inventory_type` INT(11) NOT NULL DEFAULT 0,
    `position` INT(11) NOT NULL DEFAULT 0,
    `quantity` INT(11) NOT NULL DEFAULT 0,
    `owner` TINYTEXT NOT NULL,
    `pet_id` INT(11) NOT NULL DEFAULT 0,
    `flag` INT(11) NOT NULL DEFAULT 0,
    `expiration` BIGINT(20) NOT NULL DEFAULT 0,
    `gift_from` VARCHAR(26) NOT NULL DEFAULT '',
    PRIMARY KEY (`inventory_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
