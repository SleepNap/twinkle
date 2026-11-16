-- ============================================================
-- MySQL V19: storage_records / storage_items（账号仓库 storage/trunk 持久化）
-- ============================================================
-- storage_records 是账号级仓库头（按 account_id + world 唯一，row_version 做乐观锁）；
-- storage_items 是仓库物品行，列集与 inventory_items 对齐（V3 + V11 宠物列），
-- 槽位唯一键用 slot_index（position 是 SQL 关键字，不用于列名）。
-- 索引内联在 CREATE TABLE 中：MySQL 不支持 CREATE INDEX IF NOT EXISTS，
-- 内联 KEY/UNIQUE KEY 才能随 CREATE TABLE IF NOT EXISTS 一起保持幂等。
-- ============================================================
CREATE TABLE IF NOT EXISTS `storage_records` (
    `storage_record_id` INT(11) NOT NULL AUTO_INCREMENT,
    `account_id` INT(11) NOT NULL DEFAULT 0,
    `world` INT(11) NOT NULL DEFAULT 0,
    `slot_count` INT(11) NOT NULL DEFAULT 4,
    `meso` INT(11) NOT NULL DEFAULT 0,
    `row_version` INT(11) NOT NULL DEFAULT 0,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`storage_record_id`),
    UNIQUE KEY `idx_storage_records_account_world` (`account_id`, `world`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `storage_items` (
    `storage_item_id` INT(11) NOT NULL AUTO_INCREMENT,
    `storage_record_id` INT(11) NOT NULL DEFAULT 0,
    `account_id` INT(11) NOT NULL DEFAULT 0,
    `inventory_type` INT(11) NOT NULL DEFAULT 0,
    `slot_index` INT(11) NOT NULL DEFAULT 0,
    `item_id` INT(11) NOT NULL DEFAULT 0,
    `quantity` INT(11) NOT NULL DEFAULT 0,
    `owner` TINYTEXT NOT NULL,
    `pet_id` INT(11) NOT NULL DEFAULT 0,
    `flag` INT(11) NOT NULL DEFAULT 0,
    `expiration` BIGINT(20) NOT NULL DEFAULT -1,
    `gift_from` VARCHAR(26) NOT NULL DEFAULT '',
    `cash_id` INT NOT NULL DEFAULT 0,
    `upgrade_slots` TINYINT NOT NULL DEFAULT 0,
    `level` SMALLINT NOT NULL DEFAULT 0,
    `str_stat` SMALLINT NOT NULL DEFAULT 0,
    `dex_stat` SMALLINT NOT NULL DEFAULT 0,
    `int_stat` SMALLINT NOT NULL DEFAULT 0,
    `luk_stat` SMALLINT NOT NULL DEFAULT 0,
    `hp` SMALLINT NOT NULL DEFAULT 0,
    `mp` SMALLINT NOT NULL DEFAULT 0,
    `w_atk` SMALLINT NOT NULL DEFAULT 0,
    `m_atk` SMALLINT NOT NULL DEFAULT 0,
    `w_def` SMALLINT NOT NULL DEFAULT 0,
    `m_def` SMALLINT NOT NULL DEFAULT 0,
    `acc` SMALLINT NOT NULL DEFAULT 0,
    `avoid` SMALLINT NOT NULL DEFAULT 0,
    `hands` SMALLINT NOT NULL DEFAULT 0,
    `speed` SMALLINT NOT NULL DEFAULT 0,
    `jump` SMALLINT NOT NULL DEFAULT 0,
    `vicious` TINYINT NOT NULL DEFAULT 0,
    `item_level` TINYINT NOT NULL DEFAULT 0,
    `item_exp` BIGINT NOT NULL DEFAULT 0,
    `ring_id` INT NOT NULL DEFAULT 0,
    `pet_name` VARCHAR(13) NOT NULL DEFAULT '',
    `pet_level` TINYINT NOT NULL DEFAULT 1,
    `pet_closeness` SMALLINT NOT NULL DEFAULT 0,
    `pet_fullness` TINYINT NOT NULL DEFAULT 100,
    `pet_attribute` SMALLINT NOT NULL DEFAULT 0,
    `pet_skill` SMALLINT NOT NULL DEFAULT 0,
    `pet_remain_life` INT NOT NULL DEFAULT 18000,
    `item_attribute` SMALLINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`storage_item_id`),
    KEY `idx_storage_items_record` (`storage_record_id`),
    KEY `idx_storage_items_account` (`account_id`),
    UNIQUE KEY `idx_storage_items_slot` (`storage_record_id`, `inventory_type`, `slot_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
