-- ============================================================
-- PostgreSQL V19: storage_records / storage_items（账号仓库 storage/trunk 持久化）
-- ============================================================
-- storage_records 是账号级仓库头（按 account_id + world 唯一，row_version 做乐观锁）；
-- storage_items 是仓库物品行，列集与 inventory_items 对齐（V3 + V11 宠物列），
-- 槽位唯一键用 slot_index（position 是 SQL 关键字，不用于列名）。
-- ============================================================
CREATE TABLE IF NOT EXISTS storage_records (
    storage_record_id SERIAL PRIMARY KEY,
    account_id INTEGER NOT NULL DEFAULT 0,
    world INTEGER NOT NULL DEFAULT 0,
    slot_count INTEGER NOT NULL DEFAULT 4,
    meso INTEGER NOT NULL DEFAULT 0,
    row_version INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS')
);
-- 一个账号在一个世界只有一份仓库：唯一索引同时承担按账号查仓库头的查询。
CREATE UNIQUE INDEX IF NOT EXISTS idx_storage_records_account_world ON storage_records(account_id, world);

CREATE TABLE IF NOT EXISTS storage_items (
    storage_item_id SERIAL PRIMARY KEY,
    storage_record_id INTEGER NOT NULL DEFAULT 0,
    account_id INTEGER NOT NULL DEFAULT 0,
    inventory_type INTEGER NOT NULL DEFAULT 0,
    slot_index INTEGER NOT NULL DEFAULT 0,
    item_id INTEGER NOT NULL DEFAULT 0,
    quantity INTEGER NOT NULL DEFAULT 0,
    owner TEXT NOT NULL DEFAULT '',
    pet_id INTEGER NOT NULL DEFAULT 0,
    flag INTEGER NOT NULL DEFAULT 0,
    expiration BIGINT NOT NULL DEFAULT -1,
    gift_from VARCHAR(26) NOT NULL DEFAULT '',
    cash_id INTEGER NOT NULL DEFAULT 0,
    upgrade_slots SMALLINT NOT NULL DEFAULT 0,
    level SMALLINT NOT NULL DEFAULT 0,
    str_stat SMALLINT NOT NULL DEFAULT 0,
    dex_stat SMALLINT NOT NULL DEFAULT 0,
    int_stat SMALLINT NOT NULL DEFAULT 0,
    luk_stat SMALLINT NOT NULL DEFAULT 0,
    hp SMALLINT NOT NULL DEFAULT 0,
    mp SMALLINT NOT NULL DEFAULT 0,
    w_atk SMALLINT NOT NULL DEFAULT 0,
    m_atk SMALLINT NOT NULL DEFAULT 0,
    w_def SMALLINT NOT NULL DEFAULT 0,
    m_def SMALLINT NOT NULL DEFAULT 0,
    acc SMALLINT NOT NULL DEFAULT 0,
    avoid SMALLINT NOT NULL DEFAULT 0,
    hands SMALLINT NOT NULL DEFAULT 0,
    speed SMALLINT NOT NULL DEFAULT 0,
    jump SMALLINT NOT NULL DEFAULT 0,
    vicious SMALLINT NOT NULL DEFAULT 0,
    item_level SMALLINT NOT NULL DEFAULT 0,
    item_exp BIGINT NOT NULL DEFAULT 0,
    ring_id INTEGER NOT NULL DEFAULT 0,
    pet_name VARCHAR(13) NOT NULL DEFAULT '',
    pet_level SMALLINT NOT NULL DEFAULT 1,
    pet_closeness SMALLINT NOT NULL DEFAULT 0,
    pet_fullness SMALLINT NOT NULL DEFAULT 100,
    pet_attribute SMALLINT NOT NULL DEFAULT 0,
    pet_skill SMALLINT NOT NULL DEFAULT 0,
    pet_remain_life INTEGER NOT NULL DEFAULT 18000,
    item_attribute SMALLINT NOT NULL DEFAULT 0
);

-- 按仓库读全部行、账号删除清理；唯一索引保证同一仓库同一栏目的槽位不重复。
CREATE INDEX IF NOT EXISTS idx_storage_items_record ON storage_items(storage_record_id);
CREATE INDEX IF NOT EXISTS idx_storage_items_account ON storage_items(account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_storage_items_slot ON storage_items(storage_record_id, inventory_type, slot_index);
