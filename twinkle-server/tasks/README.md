# Twinkle 实施任务清单

按 [ARCHITECTURE.md](../../ARCHITECTURE.md)（第十一节路线图）拆分。每个里程碑一个独立任务文档，可分开实现。

## 任务总览

| 任务 | 标题 | 目标 | 前置依赖 | 状态 |
|---|---|---|---|---|
| [M0](M0-skeleton-and-foundation.md) | 骨架与基础设施 | 工程骨架 + 数据层 + 热更新地基 + 预算基线，验证关键技术可行 | — | 进行中（主体完成，实测类收尾） |
| [M1](M1-protocol-and-netty.md) | 协议与 Netty | 客户端登录、进图 | M0 | 进行中（核心完成，剩真实客户端验收） |
| [M2](M2-game-logic.md) | 游戏逻辑重写 | 状态/逻辑分离重写，行为对齐参考项目 | M1 | 进行中（进图打通 + 核心机制 + 逻辑侧 parity 完成；剩真实录包回放/客户端验收） |
| [M3](M3-http-ai-reload.md) | HTTP + AI + 渐进重载 | /internal+/api、LangChain4j、按实体渐进重载 | M2 | 完成（第 1 节 http-api、第 2 节 ai、第 3 节渐进重载、第 5 节协议层接入全部落地） |
| [M4](M4-plugin-hotreload-channels.md) | 插件 + 热更新全通 + 频道三机制 | 插件可装卸、L1-L4 全通、悄悄话/CC 走三机制 | M3 | 完成 |
| [M5](M5-webconsole-migration.md) | Web 控制台 + 迁移 | 控制台后端 API、单库迁移、上线切换 | M4 | 完成（后端面；前端页面未做，见任务文档"完成范围诚实标注"） |
| [M6](M6-distributed.md) | 分布式 | 多机部署、玩家换频道、升级滚动 | M5 | 未开始 |

## 推进顺序

严格按 M0 → M1 → M2 → M3 → M4 → M5 → M6 顺序推进，后一任务依赖前一任务的验收通过。

## 状态约定

- **未开始**：尚未动工
- **进行中**：正在实现，任务文档内 checkbox 勾选进度
- **完成**：该任务全部验收标准通过，且在 README 总览表更新

每个任务的详细验收标准见各任务文档"验收标准"一节，全部勾选才可标记完成。

## 相关

- 权威规范：[ARCHITECTURE.md](../../ARCHITECTURE.md)
- 参考项目（parity 真值）：北斗 `E:\LocalGit\GitHub\BeiDou-Server`
