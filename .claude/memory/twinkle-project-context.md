---
name: twinkle-project-context
description: twinkle 项目背景（MapleStory v83 后台，架构定稿于 ARCHITECTURE.md）
metadata:
  type: project
---

twinkle 是**热更新、扩展性好的冒险岛后台**（MapleStory v83 服务端），MIT 协议。权威规范是 `ARCHITECTURE.md`（设计决策、模块划分、运行拓扑均以该文档为准），M0-M6 实施任务拆在 `twinkle-server/tasks/`。

**进度（2026-08-06）**：M0 骨架与基础设施完成；**M1（协议+Netty+登录）核心完成**（net-packet 协议层、data MyBatis-Flex、net-netty、login、bootstrap 装配、parity 录包回放），全链路 E2E 验证通过。M1 剩真实 v83 客户端接入验收。**M2（游戏逻辑重写）进图打通 + 脚本引擎 + WZ 数据 + 核心机制全部完成 + parity 逻辑对照**：进图链路（WZ foothold + data↔domain 投影 + 频道初版 + SET_FIELD 包 + E2E）；脚本引擎（host 契约 + ScriptManager + L2 热重载）；Item.wz/Mob.wz + WzCache；核心机制六件套（背包/战斗/移动/交易/任务/生命恢复）统一 CharacterState spi 接口 + 版本门；parity 逻辑公式对照（伤害/落点 vs 参考项目）。M2 剩 parity 真实录包回放（需客户端素材）、协议 handler 接入转 M3（见 M3 文档第 5 节）。

**评审跟进（2026-08-06，见 `docs/architecture-review.md` 决策记录）**：二轮评审采纳 A 组落地——R3 版本门前移（core `hotreload.versioned`）、R4/R5/R15/R14 文档、R10 可观测性地基（core `observability`：Metrics/Health/MdcKeys，HTTP 绑定留 M3）、安全门槛（gitignore、`SqlInjectionScanTest`、注入 demo）。C1 已清（CLAUDE.md 无 Flyway）。推迟项见评审决策记录。

**How to apply:** 改动前先读 `ARCHITECTURE.md`；按 `tasks/README.md` 顺序推进 M0→M6。关键红线：v83 协议字节级兼容、`newmaple` 库兼容、2C2G 强制单进程、状态与逻辑分离、可替换层不得引用稳定层具体类。参考兄弟项目时遵守 [[reference-projects-discipline]]；M1 的字节级验证方式与关键坑见 [[m1-progress]]；M2 进图字节结构（SET_FIELD/addCharacterInfo）与数据映射坑见 [[m2-progress]]。所有文档/注释用中文。
