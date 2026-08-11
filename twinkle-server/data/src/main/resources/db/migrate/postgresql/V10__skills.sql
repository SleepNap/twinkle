-- v83 兼容技能存档。
CREATE TABLE skills (
    id SERIAL PRIMARY KEY,
    skillid INTEGER NOT NULL DEFAULT 0,
    characterid INTEGER NOT NULL DEFAULT 0,
    skilllevel INTEGER NOT NULL DEFAULT 0,
    masterlevel INTEGER NOT NULL DEFAULT 0,
    expiration BIGINT NOT NULL DEFAULT -1,
    UNIQUE (skillid, characterid)
);
