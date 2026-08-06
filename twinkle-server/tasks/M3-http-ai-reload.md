# M3：HTTP + AI + 按实体渐进重载

> 对应 [ARCHITECTURE.md](../../ARCHITECTURE.md) 第二节（原生 HTTP / LangChain4j / Bucket4j）、第四节 4.6.6（第三方 HTTP 跨进程取数三路）、第五节 5.3（按实体渐进重载）、第八节（AI 集成）、第十节红线、第十一节 M3。
>
> 状态：未开始 ｜ 前置依赖：M2 ｜ 影响模块：`http-api`、`ai`、`core`（重载）、`channel`（RPC 端）

## 目标

HTTP 重做（`/internal` + `/api`）+ LangChain4j AI + 按实体渐进重载。

**交付**：API 可调、AI 流式+工具可用、重载无复制 bug。

## 任务清单

### 1. http-api（Micronaut Controller）

- [ ] `/internal/v1/*`（官网转调，无需限流或弱限流）
- [ ] `/api/v1/*`（第三方，**限流 + 版本化**，Bucket4j 限流）
- [ ] HTTP 线程投递游戏线程，禁止直踩游戏内存对象
- [ ] HTTP 与游戏 Netty 隔离 EventLoop（红线 4，M1 已落地此处保持）
- [ ] 数据三路（跨进程取数规范，M3 单进程内先按此形态实现）：
  - [ ] ① 查 DB（主路）：市场记录、商店快照、角色存档
  - [ ] ② 经 service 接口：事务性操作（改密、封禁），经 service → RPC 到频道
  - [ ] ③ 事件驱动快照：频道进程推变更 → 管理进程只读镜像 → HTTP 读镜像
  - [ ] **镜像纪律**：镜像是单向、只读，禁止经镜像回写（共享状态单一属主铁律）

### 2. ai（LangChain4j）

- [ ] `AiServices` 声明式 Agent + `@Tool` 注解
- [ ] 多工具调用靠模型原生 function calling 自动循环
- [ ] 流式 `TokenStream` + 工具调用原生合一
- [ ] 场景：AI 报表 / AI 数据统计 / 多工具调用 / 每日总结（`@Scheduled`）/ 客户端流式接口
- [ ] 结构化输出：返回 POJO/Enum/JSON 自动解析
- [ ] RAG 备查：WZ / 游戏知识库
- [ ] **AI 工具不得直踩游戏内存对象**，只经 application service 接口
- [ ] 计费 / 记忆 / 配置落 SQLite（复用 Dao 设计）

### 3. 按实体渐进重载（L3）

- [ ] 重载原子单元 = 单个玩家/频道（**绝不做全服同步原子重载**）
- [ ] 每个实体只在**无在途操作的安全点**切换：
  - [ ] 丢物：单操作（单 tick 内删 item）天然安全
  - [ ] 交易：长操作，等其自然结束（秒级）或显式中断 + 回滚（双方背包归位，玩家看到"交易被取消"）
- [ ] 写路径带"版本门"：重载后旧逻辑的迟到写操作要能识别并丢弃/重放
- [ ] 可感知极限是"交易被中断"，不是"东西没了/多出来了"
- [ ] 验证：重载过程中并发交易/丢物，无复制 bug、无丢锁

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

- [ ] **移动**：`RecvOpcode.PLAYER_MOVE` handler → `MovementSystem.move` → 位置/朝向广播
- [ ] **战斗**：近战/远程/魔法攻击包 handler → `CombatSystem.physicalAttack` → 伤害广播（怪物扣血/死亡/击退）
- [ ] **刷怪/掉落**：`SpawnPoint` 重生调度 + `MapleMonster` 实例化 + 死亡掉落
- [ ] **交易**：`TRADE_*` opcode 系列 handler → `TradeSystem`（双网络会话状态机 + 结算包）
- [ ] **NPC/任务脚本挂接**：NPC 对话/任务脚本经 `ScriptManager` 调用 → `QuestSystem`
- [ ] **任务/背包存档**：V3 迁移建 `queststatus`/`inventoryitems` 表（newmaple 兼容），QuestStatus/Inventory 持久化
- [ ] **物品使用/装备穿戴**：使用类物品（HP 药等）handler → `ItemSystem` + `ItemData` 效果

> 接入时保持状态/逻辑分离纪律：handler 只做"收包→调 system→发包"，判定与状态变更一律在
> system（经 spi 接口），不把逻辑写进 handler（红线 8/11/12）。

## 验收标准

- [ ] `/internal/v1` 与 `/api/v1` 可调，`/api/v1` 限流生效且版本化
- [ ] AI 流式 + 工具调用可用（报表/统计场景跑通）
- [ ] 按实体渐进重载无复制 bug（交易/丢物并发下验证）
- [ ] 镜像单向只读纪律架构测试通过

## 风险与注意

- **HTTP/AI 直踩游戏内存**是架构级红线：M3 是 http-api 与 ai 首次落地，接口边界（service 接口）必须守住，靠架构测试固化。
- **渐进重载的"版本门"**是防复制 bug 的关键设计（5.3），M3 落地，验收标准以"无复制 bug"为准而非"同步原子重载"。
- **镜像禁止回写**：写操作一律走第②路经 service 接口（单一属主铁律）。
- AI 工具权限与 HTTP 同约束（不直踩游戏内存）。
