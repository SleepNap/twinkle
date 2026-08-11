-- v83 兼容技能存档。
CREATE TABLE `skills` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `skillid` INT NOT NULL DEFAULT 0,
    `characterid` INT NOT NULL DEFAULT 0,
    `skilllevel` INT NOT NULL DEFAULT 0,
    `masterlevel` INT NOT NULL DEFAULT 0,
    `expiration` BIGINT NOT NULL DEFAULT -1,
    PRIMARY KEY (`id`),
    UNIQUE KEY `skillpair` (`skillid`, `characterid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
