---
name: db-naming-migration-standard
description: 数据库命名与迁移规范（表≥2词、snake_case、禁关键字、common/sqlite/pg/mysql 目录、禁 -- dialect 节）
metadata:
  type: feedback
---

用户明确要求（2026-08-09）的数据库规范，**必须遵守**，已同步进根 `CLAUDE.md`"数据库命名与迁移规范"节：

1. **表名 ≥ 2 个单词**（如 `player_session`、`quest_status`），禁止单单词表名；字段名 ≥ 1 个单词。
2. **多单词用 `_` 连接**，统一 `snake_case`；禁止无隔离拼接（`nxcredit`❌）与驼峰（`nxCredit`❌）。
3. **禁止 SQL 关键字作表名/字段名**（`int`、`order`、`group`…），保留语义则改名（`int` → `int_stat`）。
4. **迁移目录结构**：`db/migrate/{common,sqlite,postgresql,mysql}/`。三方言一致放 `common/`，差异分散到各自目录。
5. **禁止 `-- dialect:xxx` 注释节**（旧机制废除），方言差异靠目录表达。
6. 迁移命名 `V<数字>__<snake_case>.sql`；结构 DDL 与 seed 数据分迁移。

**Why:** 此前所有迁移违反此规范（`accounts` 单表名、`nxCredit` 驼峰、`"int"` 关键字字段、`-- dialect:` 节）。用户两次强调"上次说记住了结果没写"——**规范必须落文档，不能只口头应承**。

**How to apply:** 写任何建表/迁移 SQL 前对照本规范；改 V1-V7 时重写为目录结构 + snake_case；旧库删除重建。相关：[[twinkle-project-context]]
