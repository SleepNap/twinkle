-- ============================================================
-- V3: 任务与背包存档表（架构 M3-5：QuestStatus/Inventory 持久化）
-- ============================================================
-- 表结构对齐 newmaple 库（红线 2：MySQL newmaple 库兼容，表结构不改）。
-- queststatus/questprogress/inventoryitems 结构参考 BeiDou-Server（OdinMS 系），
-- 按 twinkle 三方言适配自研。
-- 方言节：-- dialect:sqlite / -- dialect:postgresql / -- dialect:mysql
-- 注意：节内语句之间【不要用空行】——空行 = 节结束回全方言。
-- ============================================================

-- dialect:sqlite
CREATE TABLE queststatus (
    queststatusid INTEGER PRIMARY KEY AUTOINCREMENT,
    characterid INTEGER NOT NULL DEFAULT 0,
    quest INTEGER NOT NULL DEFAULT 0,
    status INTEGER NOT NULL DEFAULT 0,
    time INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE questprogress (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    characterid INTEGER NOT NULL DEFAULT 0,
    queststatusid INTEGER NOT NULL DEFAULT 0,
    progressid INTEGER NOT NULL DEFAULT 0,
    progress TEXT NOT NULL DEFAULT ''
);
CREATE TABLE inventoryitems (
    inventoryitemid INTEGER PRIMARY KEY AUTOINCREMENT,
    type INTEGER NOT NULL DEFAULT 0,
    characterid INTEGER NOT NULL DEFAULT 0,
    accountid INTEGER NOT NULL DEFAULT 0,
    itemid INTEGER NOT NULL DEFAULT 0,
    inventorytype INTEGER NOT NULL DEFAULT 0,
    position INTEGER NOT NULL DEFAULT 0,
    quantity INTEGER NOT NULL DEFAULT 0,
    owner TEXT NOT NULL DEFAULT '',
    petid INTEGER NOT NULL DEFAULT 0,
    flag INTEGER NOT NULL DEFAULT 0,
    expiration INTEGER NOT NULL DEFAULT 0,
    giftFrom TEXT NOT NULL DEFAULT ''
);
-- dialect:postgresql
CREATE TABLE queststatus (
    queststatusid SERIAL PRIMARY KEY,
    characterid INTEGER NOT NULL DEFAULT 0,
    quest INTEGER NOT NULL DEFAULT 0,
    status INTEGER NOT NULL DEFAULT 0,
    time INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE questprogress (
    id SERIAL PRIMARY KEY,
    characterid INTEGER NOT NULL DEFAULT 0,
    queststatusid INTEGER NOT NULL DEFAULT 0,
    progressid INTEGER NOT NULL DEFAULT 0,
    progress VARCHAR(15) NOT NULL DEFAULT ''
);
CREATE TABLE inventoryitems (
    inventoryitemid SERIAL PRIMARY KEY,
    type SMALLINT NOT NULL DEFAULT 0,
    characterid INTEGER NOT NULL DEFAULT 0,
    accountid INTEGER NOT NULL DEFAULT 0,
    itemid INTEGER NOT NULL DEFAULT 0,
    inventorytype INTEGER NOT NULL DEFAULT 0,
    position INTEGER NOT NULL DEFAULT 0,
    quantity INTEGER NOT NULL DEFAULT 0,
    owner TEXT NOT NULL DEFAULT '',
    petid INTEGER NOT NULL DEFAULT 0,
    flag INTEGER NOT NULL DEFAULT 0,
    expiration BIGINT NOT NULL DEFAULT 0,
    giftFrom VARCHAR(26) NOT NULL DEFAULT ''
);
-- dialect:mysql
CREATE TABLE `queststatus` (
    `queststatusid` INT(11) NOT NULL AUTO_INCREMENT,
    `characterid` INT(11) NOT NULL DEFAULT 0,
    `quest` INT(11) NOT NULL DEFAULT 0,
    `status` INT(11) NOT NULL DEFAULT 0,
    `time` INT(11) NOT NULL DEFAULT 0,
    PRIMARY KEY (`queststatusid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE `questprogress` (
    `id` INT(11) NOT NULL AUTO_INCREMENT,
    `characterid` INT(11) NOT NULL DEFAULT 0,
    `queststatusid` INT(11) NOT NULL DEFAULT 0,
    `progressid` INT(11) NOT NULL DEFAULT 0,
    `progress` VARCHAR(15) NOT NULL DEFAULT '',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE `inventoryitems` (
    `inventoryitemid` INT(11) NOT NULL AUTO_INCREMENT,
    `type` TINYINT(4) NOT NULL DEFAULT 0,
    `characterid` INT(11) NOT NULL DEFAULT 0,
    `accountid` INT(11) NOT NULL DEFAULT 0,
    `itemid` INT(11) NOT NULL DEFAULT 0,
    `inventorytype` INT(11) NOT NULL DEFAULT 0,
    `position` INT(11) NOT NULL DEFAULT 0,
    `quantity` INT(11) NOT NULL DEFAULT 0,
    `owner` TINYTEXT NOT NULL,
    `petid` INT(11) NOT NULL DEFAULT 0,
    `flag` INT(11) NOT NULL DEFAULT 0,
    `expiration` BIGINT(20) NOT NULL DEFAULT 0,
    `giftFrom` VARCHAR(26) NOT NULL DEFAULT '',
    PRIMARY KEY (`inventoryitemid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
