# M3：HTTP + AI + 按实体渐进重载

> 对应 [ARCHITECTURE.md](../../ARCHITECTURE.md) 第二节（原生 HTTP / LangChain4j / Bucket4j）、第四节 4.6.6（第三方 HTTP 跨进程取数三路）、第五节 5.3（按实体渐进重载）、第八节（AI 集成）、第十节红线、第十一节 M3。
>
> 状态：未开始 ｜ 前置依赖：M2 ｜ 影响模块：`http-api`、`ai`、`core`（重载）、`channel`（RPC 端）

## 目标

HTTP 重做（`/internal` + `/api`）+ LangChain4j AI + 按实体渐进重载。

**交付**：API 可调、AI 流式+工具可用、重载无复制 bug。

## 任务清单

### 1. http-api（Micronaut Controller）

- [x] `/internal/v1/*`（官网转调，无需限流或弱限流）
- [x] `/api/v1/*`（第三方，**限流 + 版本化**，Bucket4j 限流）
- [x] HTTP 线程投递游戏线程，禁止直踩游戏内存对象
- [x] HTTP 与游戏 Netty 隔离 EventLoop（红线 4，M1 已落地此处保持）
- [x] 数据三路（跨进程取数规范，M3 单进程内先按此形态实现）：
  - [x] ① 查 DB（主路）：市场记录、商店快照、角色存档（`/api/v1/account/{name}`、`/api/v1/account/{id}/characters`，经 data repository）
  - [x] ② 经 service 接口：事务性操作（踢下线 `DELETE /api/v1/characters/{id}/session`），经 `AdminService`（core 公共契约）RPC 到频道（单进程直调，分进程换实现，接口不变）
  - [x] ③ 事件驱动快照：频道进程推 `PlayerOnline/PlayerOffline`（`ChannelEventPublisher` → EventBus）→ 管理进程只读镜像（`OnlinePlayerMirror`）→ HTTP 读镜像（`/internal/v1/online`、`/api/v1/online`）
  - [x] **镜像纪律**：镜像是单向、只读，禁止经镜像回写（共享状态单一属主铁律）——事件类型/镜像接口均在 core 公共底座（跨进程契约），http-api 只读镜像、写操作一律经 ② 路

> **2026-08-08 交付**：http-api 全落地。`AdminService`/`OnlinePlayerEvents` 放 core 公共底座（http-api 依赖 core+data，禁止依赖 channel/domain-game，ArchUnit 规则 1 保持绿）。限流参数经 Micronaut `@Property` 注入（`twinkle.http.api.rate-limit.*`，application.yml 默认 100 req/s）。测试：`HttpApiE2ETest`（bootstrap 完整装配，health/镜像/查DB/429 限流全绿）、`ApiRateLimiterTest`（令牌桶）、`OnlinePlayerMirrorTest`（镜像单向+幂等）、`ChannelAdminServiceTest`（踢下线经会话注册表关闭/不在线 false）。`MyBatisFlexFactory` 补齐 InventoryItem/Quest 六 Mapper 注册（查存档前置缺件）。可观测挂接：`ObservabilityConfig` 装配 `NoopMetrics`/`MemoryHealthRegistry`，限流埋点经 Metrics。

### 2. ai（LangChain4j）

- [x] `AiServices` 声明式 Agent + `@Tool` 注解
- [x] 多工具调用靠模型原生 function calling 自动循环
- [x] 流式 `TokenStream` + 工具调用原生合一
- [x] 场景：AI 报表 / AI 数据统计 / 多工具调用 / 每日总结（`@Scheduled`）/ 客户端流式接口
- [x] 结构化输出：返回 POJO/Enum/JSON 自动解析
- [ ] RAG 备查：WZ / 游戏知识库（暂缓，留后续）
- [x] **AI 工具不得直踩游戏内存对象**，只经 application service 接口
- [x] 计费 / 记忆 / 配置落 SQLite（复用 Dao 设计）

> **2026-08-11 更新**：在原 M3 Agent 地基上接入 OpenAI-compatible / DeepSeek 真实模型，并落地游戏内 `@gm` AI 值班 GM。工具扩展为在线概览、角色存档、已落库背包、账号状态，全部只读并写权威审计；玩家入口仅能查本人。模型调用走独立小线程池，会话、并发、玩家频率均有界；默认仍关闭。完整配置与未完成边界见 `docs/archived/server-agent.md`。

### 3. 按实体渐进重载（L3）

- [x] 重载原子单元 = 单个玩家/频道（**绝不做全服同步原子重载**）
- [x] 每个实体只在**无在途操作的安全点**切换：
  - [x] 丢物：单操作（单 tick 内删 item）天然安全
  - [x] 交易：长操作，等其自然结束（秒级）或显式中断 + 回滚（双方背包归位，玩家看到"交易被取消"）
- [x] 写路径带"版本门"：重载后旧逻辑的迟到写操作要能识别并丢弃/重放
- [x] 可感知极限是"交易被中断"，不是"东西没了/多出来了"
- [x] 验证：重载过程中并发交易/丢物，无复制 bug、无丢锁

> **2026-08-08 交付**：按实体渐进重载全落地。机制装配（版本门 M0/M1 定稿、M2 写路径已按版本门编写，此处只做编排）：
> - **`EntityReloadCoordinator`**（core）：按实体跟踪在途操作（begin/end 计数），`isSafe`/`safeOnly` 判定安全点——丢物等单操作实体天然安全（不进入跟踪），交易等长操作实体在途不可重载。
> - **`EntityReloadService`**（core）：编排一次重载——逐实体推进（安全点直切 / 在途显式中断），再 `VersionGate.onReload()` 换代。**绝不做全服同步原子重载**。
> - **`TradeSystem.interrupt`**：显式中断 + 回滚——清空双方出价（物品从未离背包，offer 只是承诺）+ 状态 CANCELLED，背包/meso 归位，玩家看到"交易被取消"。
> - **`PlayerInteractionHandler`** 接入协调器：invite 时 `beginOperation` 双方，exit/decline/complete 时 `endOperation`（有向无环，嵌套计数）。
> - **HTTP 运维端点**：`GET /internal/v1/reload/in-flight`（在途实体观测）、`POST /internal/v1/reload`（触发渐进重载）。
> - 测试：`EntityReloadServiceTest`（core 6 例：安全点/嵌套/渐进/不可中断跳过/迟到写 STALE/safeOnly）、`TradeSystemTest`（+3 例中断回滚）、`EntityReloadE2ETest`（bootstrap 2 例：交易在途重载中断+无复制 bug+版本门拒迟到写；空闲实体直切不打断）、`HttpApiE2ETest`（+reload 端点）。

### 4. HTTP 与游戏线程模型

- [ ] HTTP 线程投递游戏线程（投递机制，不直踩内存）
- [ ] 第三方 API 流量不挤占游戏 tick 线程

### 5. 协议层接入（承接 M2 核心机制，M2 只完成逻辑侧，此处置后补全）

> **前置事实（M2-2 已就绪，勿重复实现）**：六个可替换层 system 已落地且统一经
> `CharacterState` spi 接口 + 版本门——`ItemSystem`（背包 give/take/count）、`CombatSystem`
> + `DamageCalculator`（伤害公式）、`MovementSystem` + `MapleMap.groundBelow`（落点物理）、
> `TradeSystem` + `Trade`/`TradeSide`（状态机+双向结算）、`QuestSystem` + `QuestStatus`、
> `HealthRecoverySystem`；`MapleMonster`/`SpawnPoint`/`ItemData`/`MobData` 数据就绪；
> `ScriptManager`（host 契约 cm/qm/im/rm/em）就绪。**M2 未接协议 handler，以下为本里程碑补齐**：

- [x] **移动**：`RecvOpcode.PLAYER_MOVE` handler → `MovementSystem.move` → 位置/朝向广播（`MovePlayerHandler`，v83 9 字节头 + numCommands 片段解析，思路参考 BeiDou AbstractMovementPacketHandler）
- [x] **战斗**：近战/远程/魔法攻击包 handler → `CombatSystem.physicalAttack` → 伤害广播（怪物扣血/死亡/移除，`AttackHandler` 三类型共用解析，布局参考 BeiDou AbstractDealDamageHandler）
- [x] **刷怪/掉落**：`SpawnPoint` 实例化 + `MonsterSpawnService` 重生调度（`SPAWN_MONSTER` 广播；死亡 `KILL_MONSTER` + 重生；掉落表未解析留后续）
- [x] **交易**：`PLAYER_INTERACTION`（0x7B）子动作 handler → `TradeSystem`（双网络会话状态机 + 结算包，`PlayerInteractionHandler`，思路参考 BeiDou PlayerInteractionHandler）
- [x] **NPC/任务脚本挂接**：`NpcTalkHandler`/`NpcTalkMoreHandler` 双路由（经典 `start`+`action(status)` 重入 + 北斗 nextlevel `levelXxx` 函数派发），`ConversationScript` 每会话独立 Context 持久化；host `Cm` 扩展对话/nextlevel/能力方法（`NpcConversationHost`）
- [x] **任务/背包存档**：V3 迁移建 `queststatus`/`questprogress`/`inventoryitems` 表（newmaple 兼容）+ 实体/仓库（`QuestStatusEntity`/`InventoryItemEntity` + Flex 实现，replaceAll 覆盖落库）
- [x] **物品使用**：`UseItemHandler`（0x48）→ `ItemSystem` 扣 1 + `ItemData.stats` 效果（hp/mp 药）+ `STAT_CHANGED`

> **2026-08-06 交付**：进图后可玩闭环全部落地。前置缺件补齐——`PlayerSessionRegistry`（角色↔会话 + 广播）、bootstrap `WzConfig` 装配六个 replaceable system + Item/Mob 数据、`MapleMap` 怪物容器 + objectId、`MapleMonster.objectId`、`Character.mapObject`、断链注销（`DisconnectListener`）。E2E 全绿：`GamePlayE2ETest`（移动/战斗/刷怪/物品）、`NpcDialogE2ETest`（经典 + nextlevel 双路由）、`TradeE2ETest`（双客户端交易结算）。掉落表（mob drops）与魔法伤害公式留后续。

> 接入时保持状态/逻辑分离纪律：handler 只做"收包→调 system→发包"，判定与状态变更一律在
> system（经 spi 接口），不把逻辑写进 handler（红线 8/11/12）。

## 验收标准

- [x] `/internal/v1` 与 `/api/v1` 可调，`/api/v1` 限流生效且版本化
- [x] AI 流式 + 工具调用可用（报表/统计场景跑通）
- [x] 按实体渐进重载无复制 bug（交易/丢物并发下验证）
- [x] 镜像单向只读纪律架构测试通过

## 风险与注意

- **HTTP/AI 直踩游戏内存**是架构级红线：M3 是 http-api 与 ai 首次落地，接口边界（service 接口）必须守住，靠架构测试固化。
- **渐进重载的"版本门"**是防复制 bug 的关键设计（5.3），M3 落地，验收标准以"无复制 bug"为准而非"同步原子重载"。
- **镜像禁止回写**：写操作一律走第②路经 service 接口（单一属主铁律）。
- AI 工具权限与 HTTP 同约束（不直踩游戏内存）。
