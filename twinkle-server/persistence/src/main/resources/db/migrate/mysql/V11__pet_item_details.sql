-- 宠物实例状态与背包物品同事务保存；每列使用独立 ALTER 语句。
ALTER TABLE `inventory_items` ADD COLUMN `pet_name` VARCHAR(13) NOT NULL DEFAULT '';
ALTER TABLE `inventory_items` ADD COLUMN `pet_level` TINYINT NOT NULL DEFAULT 1;
ALTER TABLE `inventory_items` ADD COLUMN `pet_closeness` SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE `inventory_items` ADD COLUMN `pet_fullness` TINYINT NOT NULL DEFAULT 100;
ALTER TABLE `inventory_items` ADD COLUMN `pet_attribute` SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE `inventory_items` ADD COLUMN `pet_skill` SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE `inventory_items` ADD COLUMN `pet_remain_life` INT NOT NULL DEFAULT 18000;
ALTER TABLE `inventory_items` ADD COLUMN `item_attribute` SMALLINT NOT NULL DEFAULT 0;
