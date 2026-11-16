-- ============================================================
-- SQLite V20: guild_record（公会头；成员关系在 character_records.guild_id）
-- ============================================================
-- 成员关系不另建表：character_records.guild_id / guild_rank 是唯一权威（V2 已有），
-- 公会头只存公会级数据，避免「成员表 + 角色表」双写漂移。
-- row_version 用于跨频道写入的乐观锁：不一致时丢弃频道缓存重新读库。
-- ============================================================
CREATE TABLE IF NOT EXISTS guild_record (
    guild_record_id INTEGER PRIMARY KEY AUTOINCREMENT,
    world INTEGER NOT NULL DEFAULT 0,
    name TEXT NOT NULL DEFAULT '',
    leader_id INTEGER NOT NULL DEFAULT 0,
    capacity INTEGER NOT NULL DEFAULT 10,
    point_gp INTEGER NOT NULL DEFAULT 0,
    rank_title_1 TEXT NOT NULL DEFAULT 'Master',
    rank_title_2 TEXT NOT NULL DEFAULT 'Jr. Master',
    rank_title_3 TEXT NOT NULL DEFAULT 'Member',
    rank_title_4 TEXT NOT NULL DEFAULT 'Member',
    rank_title_5 TEXT NOT NULL DEFAULT 'Member',
    logo_background INTEGER NOT NULL DEFAULT 0,
    logo_background_color INTEGER NOT NULL DEFAULT 0,
    logo_mark INTEGER NOT NULL DEFAULT 0,
    logo_mark_color INTEGER NOT NULL DEFAULT 0,
    notice TEXT NOT NULL DEFAULT '',
    signature INTEGER NOT NULL DEFAULT 0,
    alliance_id INTEGER NOT NULL DEFAULT 0,
    row_version INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_guild_record_name ON guild_record(world, name);
CREATE INDEX IF NOT EXISTS idx_guild_record_world_leader ON guild_record(world, leader_id);

-- 成员列表按 guild_id 查角色表；同时服务账号删除时的公会级联。
CREATE INDEX IF NOT EXISTS idx_character_records_guild ON character_records(guild_id);
