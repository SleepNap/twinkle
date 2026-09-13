-- v83 兼容技能存档；唯一索引以角色开头，覆盖按角色读写与技能排序。
CREATE TABLE `skills` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `skillid` INT NOT NULL DEFAULT 0,
    `characterid` INT NOT NULL DEFAULT 0,
    `skilllevel` INT NOT NULL DEFAULT 0,
    `masterlevel` INT NOT NULL DEFAULT 0,
    `expiration` BIGINT NOT NULL DEFAULT -1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `skillpair` (`characterid`, `skillid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
