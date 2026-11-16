-- ============================================================
-- PostgreSQL V20: guild_record（公会头；成员关系在 character_records.guild_id）
-- ============================================================
-- 成员关系不另建表：character_records.guild_id / guild_rank 是唯一权威（V2 已有）。
-- updated_at 与 sqlite 的 datetime('now') 保持同样的 UTC 文本格式，便于字符串比较。
-- ============================================================
CREATE TABLE IF NOT EXISTS guild_record (
    guild_record_id SERIAL PRIMARY KEY,
    world INTEGER NOT NULL DEFAULT 0,
    name TEXT NOT NULL DEFAULT '',
    leader_id BIGINT NOT NULL DEFAULT 0,
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
    updated_at TEXT NOT NULL DEFAULT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_guild_record_name ON guild_record(world, name);
CREATE INDEX IF NOT EXISTS idx_guild_record_world_leader ON guild_record(world, leader_id);
CREATE INDEX IF NOT EXISTS idx_character_records_guild ON character_records(guild_id);
