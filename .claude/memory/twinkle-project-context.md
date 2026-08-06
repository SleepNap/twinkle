---
name: twinkle-project-context
description: twinkle 项目背景（MapleStory v83 后台，架构定稿于 ARCHITECTURE.md）
metadata:
  type: project
---

twinkle 是**热更新、扩展性好的冒险岛后台**（MapleStory v83 服务端），MIT 协议，当前仓库骨架阶段。权威规范是 `ARCHITECTURE.md`（设计决策、模块划分、运行拓扑均以该文档为准），M0-M6 实施任务拆在 `twinkle-server/tasks/`。

**How to apply:** 改动前先读 `ARCHITECTURE.md`；按 `tasks/README.md` 顺序推进 M0→M6。关键红线：v83 协议字节级兼容、`newmaple` 库兼容、2C2G 强制单进程、状态与逻辑分离、可替换层不得引用稳定层具体类。参考兄弟项目时遵守 [[reference-projects-discipline]]。所有文档/注释用中文。
