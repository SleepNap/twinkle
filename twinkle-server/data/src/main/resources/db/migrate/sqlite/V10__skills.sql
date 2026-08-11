-- v83 兼容技能存档。
CREATE TABLE skills (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    skillid INTEGER NOT NULL DEFAULT 0,
    characterid INTEGER NOT NULL DEFAULT 0,
    skilllevel INTEGER NOT NULL DEFAULT 0,
    masterlevel INTEGER NOT NULL DEFAULT 0,
    expiration INTEGER NOT NULL DEFAULT -1,
    UNIQUE (skillid, characterid)
);
