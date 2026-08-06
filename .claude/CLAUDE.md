# 项目级记忆

本目录为**项目级记忆**，随仓库共享（`.claude/` 会被 git 跟踪），所有在此仓库工作的 Claude 实例都会加载。

记忆文件在 `memory/` 子目录，经 @ 引用：

@memory/reference-projects-discipline.md
@memory/twinkle-project-context.md

## 记忆分层约定

| 位置 | 范围 | 是否入库 |
|---|---|---|
| `CLAUDE.md`（根） | 共享架构规范 | ✅ |
| `.claude/`（本目录） | 项目级记忆 / 指令 | ✅ |
| `CLAUDE.local.md` | 本机个人偏好 | ❌（gitignore） |
