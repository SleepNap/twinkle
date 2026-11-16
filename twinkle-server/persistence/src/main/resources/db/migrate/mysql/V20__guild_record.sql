-- ============================================================
-- MySQL V20: guild_record（公会头；成员关系在 character_records.guild_id）
-- ============================================================
-- 成员关系不另建表：character_records.guild_id / guild_rank 是唯一权威（V2 已有）。
-- MySQL 不支持 CREATE INDEX IF NOT EXISTS，索引内联在 CREATE TABLE 里靠 IF NOT EXISTS 幂等。
-- ============================================================
CREATE TABLE IF NOT EXISTS `guild_record` (
    `guild_record_id` INT(11) NOT NULL AUTO_INCREMENT,
    `world` INT(11) NOT NULL DEFAULT 0,
    `name` VARCHAR(32) NOT NULL DEFAULT '',
    `leader_id` BIGINT NOT NULL DEFAULT 0,
    `capacity` INT(11) NOT NULL DEFAULT 10,
    `point_gp` INT(11) NOT NULL DEFAULT 0,
    `rank_title_1` VARCHAR(32) NOT NULL DEFAULT 'Master',
    `rank_title_2` VARCHAR(32) NOT NULL DEFAULT 'Jr. Master',
    `rank_title_3` VARCHAR(32) NOT NULL DEFAULT 'Member',
    `rank_title_4` VARCHAR(32) NOT NULL DEFAULT 'Member',
    `rank_title_5` VARCHAR(32) NOT NULL DEFAULT 'Member',
    `logo_background` INT(11) NOT NULL DEFAULT 0,
    `logo_background_color` INT(11) NOT NULL DEFAULT 0,
    `logo_mark` INT(11) NOT NULL DEFAULT 0,
    `logo_mark_color` INT(11) NOT NULL DEFAULT 0,
    `notice` VARCHAR(255) NOT NULL DEFAULT '',
    `signature` INT(11) NOT NULL DEFAULT 0,
    `alliance_id` INT(11) NOT NULL DEFAULT 0,
    `row_version` INT(11) NOT NULL DEFAULT 0,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`guild_record_id`),
    UNIQUE KEY `idx_guild_record_name` (`world`, `name`),
    KEY `idx_guild_record_world_leader` (`world`, `leader_id`)
);

-- idx_character_records_guild 建在 character_records（既有表）上；MySQL 不支持 CREATE INDEX IF NOT EXISTS，
-- 为保证迁移可重复执行，MySQL 侧不建该索引（功能不受影响，仅成员查询少一个索引）。
