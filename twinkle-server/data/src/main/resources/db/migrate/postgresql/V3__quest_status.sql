-- ============================================================
-- PostgreSQL V3: quest_status / quest_progress / inventory_items（任务/背包存档）
-- ============================================================
-- quest_status 九列一次建全（原五列 + expires/forfeited/completed/info 四列）。
-- ============================================================
CREATE TABLE IF NOT EXISTS quest_status (
    quest_status_id SERIAL PRIMARY KEY,
    character_id INTEGER NOT NULL DEFAULT 0,
    quest INTEGER NOT NULL DEFAULT 0,
    status INTEGER NOT NULL DEFAULT 0,
    time INTEGER NOT NULL DEFAULT 0,
    expires BIGINT NOT NULL DEFAULT 0,
    forfeited INTEGER NOT NULL DEFAULT 0,
    completed INTEGER NOT NULL DEFAULT 0,
    info INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS quest_progress (
    id SERIAL PRIMARY KEY,
    character_id INTEGER NOT NULL DEFAULT 0,
    quest_status_id INTEGER NOT NULL DEFAULT 0,
    progress_id INTEGER NOT NULL DEFAULT 0,
    progress VARCHAR(15) NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS inventory_items (
    inventory_item_id SERIAL PRIMARY KEY,
    type SMALLINT NOT NULL DEFAULT 0,
    character_id INTEGER NOT NULL DEFAULT 0,
    account_id INTEGER NOT NULL DEFAULT 0,
    item_id INTEGER NOT NULL DEFAULT 0,
    inventory_type INTEGER NOT NULL DEFAULT 0,
    position INTEGER NOT NULL DEFAULT 0,
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
    ring_id INTEGER NOT NULL DEFAULT 0
);
