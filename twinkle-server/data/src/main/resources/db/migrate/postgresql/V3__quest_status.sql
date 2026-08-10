-- ============================================================
-- PostgreSQL V3: quest_status / quest_progress / inventory_items（任务/背包存档）
-- ============================================================
-- quest_status 九列一次建全（原五列 + expires/forfeited/completed/info 四列）。
-- ============================================================
CREATE TABLE quest_status (
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
CREATE TABLE quest_progress (
    id SERIAL PRIMARY KEY,
    character_id INTEGER NOT NULL DEFAULT 0,
    quest_status_id INTEGER NOT NULL DEFAULT 0,
    progress_id INTEGER NOT NULL DEFAULT 0,
    progress VARCHAR(15) NOT NULL DEFAULT ''
);
CREATE TABLE inventory_items (
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
    expiration BIGINT NOT NULL DEFAULT 0,
    gift_from VARCHAR(26) NOT NULL DEFAULT ''
);
