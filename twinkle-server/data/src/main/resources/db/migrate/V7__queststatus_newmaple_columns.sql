-- ============================================================
-- V7: queststatus 补 newmaple 列（架构 M5-2 单库迁移）
-- ============================================================
-- 参考项目（newmaple 系）的 queststatus 有 9 列，twinkle V3 建表只建了 5 列，
-- 缺 expires/forfeited/completed/info 四列。老库数据导入时这四列无落点，
-- 故补列（红线 2：扩展走 migration ADD COLUMN，禁止单条 ALTER 串接多列——
-- 每列一条独立 ALTER）。
--
-- 方言节：-- dialect:sqlite / -- dialect:postgresql / -- dialect:mysql
-- 注意：节内语句之间【不要用空行】——空行 = 节结束回全方言。
-- ============================================================

-- dialect:sqlite
ALTER TABLE queststatus ADD COLUMN expires INTEGER NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN forfeited INTEGER NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN completed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN info INTEGER NOT NULL DEFAULT 0;
-- dialect:postgresql
ALTER TABLE queststatus ADD COLUMN expires BIGINT NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN forfeited INTEGER NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN completed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN info INTEGER NOT NULL DEFAULT 0;
-- dialect:mysql
ALTER TABLE queststatus ADD COLUMN `expires` BIGINT(20) NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN `forfeited` INT(11) NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN `completed` INT(11) NOT NULL DEFAULT 0;
ALTER TABLE queststatus ADD COLUMN `info` TINYINT(3) NOT NULL DEFAULT 0;
