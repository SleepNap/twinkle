# M2：游戏逻辑重写（状态/逻辑分离）

> 对应 [ARCHITECTURE.md](../../ARCHITECTURE.md) 第三节（domain-game 纯数据模型 + 索引/服务、状态/逻辑分层）、第五节 5.3（按实体渐进重载）、第十节红线（newmaple 兼容 / 存档格式兼容 / 游戏对象不进容器）、第十一节 M2。
>
> 状态：未开始 ｜ 前置依赖：M1 ｜ 影响模块：`domain-game`、`domain-script`、`wz-provider`、`channel`

## 目标

游戏逻辑**重写**为状态/逻辑分离，参考项目（北斗）作 parity 真值，行为对齐。这是工作量最大的里程碑。

**交付**：状态/逻辑分离重写，录包回放 / 双跑对照行为对齐参考项目。

## 任务清单

### 1. domain-game：纯数据模型（稳定层）

- [x] 游戏对象模型（Character/Item/Equip/Inventory/InventoryType/ItemConstants/SkillEntry/MapleMap/Portal/SpawnPoint/PortalType）**纯数据**（版本化 schema，Character 实现 Versioned）+ 手动 new 不进容器（红线 4）；WZ 加载填充留 M2-4
- [x] 稳定层：数据模型为稳定层，可替换层经接口访问（M2-2 定义接口边界）
- [x] `characters` 存档结构兼容：74 列全映射到 domain-game.Character（红线 3）；keymap/skills/queststatus/inventory 结构由后续 M2-2/M2-3 补充映射
- [x] MySQL `newmaple` 库兼容：表结构不改（红线 2，V2 建表已对齐）

> 2026-08-06 第一片交付：Character（74 列 + 内存态背包/技能 + Versioned 契约）、Item/Equip（copy 深拷贝）、Inventory（槽位分配）、InventoryType、ItemConstants（id→背包类型推导）、SkillEntry。17 测试通过。domain-game 新增 core 依赖（Versioned 热重载契约，上层依赖底座合法）。

### 2. 状态/逻辑分离

- [x] 可替换层：游戏逻辑系统（systems）在 `org.gms.replaceable`，经 `org.gms.domain.game.spi` 接口访问稳定层，绝不反向依赖（ArchUnit 规则 3 强制）
- [x] 可替换层**不得引用游戏内存对象的具体类**（只能用接口或数据投影，防 CCE，红线 8/11）
- [x] 可替换层**不得持有跨操作状态**（逻辑无状态化，红线 8/12）；写回前过版本门（架构 5.3）
- [x] 逻辑无状态化：从接口读 → 计算 → 写回（演示：HealthRecoverySystem）
- [x] **背包系统**（2026-08-06）：`CharacterState`（spi）接口扩展 addItem/getItemCount/removeItem（经接口操作，可替换层不触碰 Inventory/Item 具体类，红线 11）+ domain.Character 实现（容量预检无副作用、堆叠、空槽分配）+ `replaceable.ItemSystem`（give/take/count，版本门）；关键——规则 3 只禁 replaceable 依赖 domain.game/inventory/skill/data.entity，**item/mob/map 包（ItemData/MobData/MapleMap）可依赖**
- [x] **战斗系统**（2026-08-06）：`MapleMonster` 运行时怪物（MobData + HP/位置/存活）+ `replaceable.DamageCalculator`（v83 物理公式 `ceil((weaponMult×主属性+副属性)/100×watk)`，思路参考 BeiDou）+ `CombatSystem.physicalAttack`（版本门 + 扣血 + 防御削减）
- [x] **移动系统**（2026-08-06）：CharacterState 扩展位置 x/y + `MapleMap.groundBelow`（foothold 落点插值，v83 坐标系 y 向下重力落地）+ `MovementSystem.move`（版本门 + 落地判定）
- [x] **交易系统**（2026-08-06）：`Trade`/`TradeSide` 状态机（ACTIVE → 双方锁定 → DONE/CANCELLED）+ `TradeSystem`（offer/offerMeso/lock/complete，物品+meso 双向结算，经 ItemSystem；跨 tick 操作按 5.3 单实体安全点）
- [x] **任务系统**（2026-08-06）：`QuestStatus`（NOT_STARTED→STARTED→COMPLETED + 进度计数）+ CharacterState 任务接口（start/complete/setQuestProgress）+ `QuestSystem`（判定 + 版本门）

> 2026-08-06 第五片交付：核心机制全部落地为可替换层 system（模板 HealthRecovery/ItemSystem/CombatSystem/MovementSystem/TradeSystem/QuestSystem），统一经 CharacterState spi 接口 + 版本门。**协议层接入**（移动包 PLAYER_MOVE、攻击包、交易包、NPC/任务脚本挂接）留 M2-5/M3 网络 handler 层；任务存档表留 M2-6。

> 2026-08-06 第一片交付：`spi/CharacterState` 接口 + Character 实现；`replaceable/HealthRecoverySystem` 演示（接口访问 + 版本门拒迟到写）；ArchUnit 规则 3 放行 spi 接口包（ArchUnit 1.5 基础链无 ignoreDependency，改显式列举具体包）。Lombok 引入（红线 11），Character/Item/Equip 重构为 @Getter/@Setter。

### 3. wz-provider（WZ 解析）

- [x] MapleMap / Portal / SpawnPoint / PortalType 纯数据模型（domain-game，2026-08-06，9 测试）——解析填充目标就绪
- [x] **WZ 解析第一片**（2026-08-06）：`WzNode` 数据树 + `WzXmlParser`（DOM 解析 img XML，防 XXE）+ `MapLoader`（Map.wz → MapleMap 填充 info/portal/life）；内嵌测试 5 + **真实北斗 WZ 数据手动验证通过**（新手村 100000000：town/portals；野图 100000004：24 怪物刷新点）
- [x] **foothold 完整解析**（2026-08-06）：`MapleFoothold`（layer→group→id、x1/y1/x2/y2/prev/next/force，稳定层纯数据）+ MapLoader 三级遍历填充；真实数据验证：新手村 338 条、野图 114 条地面线段（服务端物理/掉宝找落点用，客户端 WZ 自取不下发）
- [x] **Item.wz / Mob.wz 解析**（2026-08-06）：`ItemData`/`MobData` 纯数据（domain-game item/mob 包，Serializable）+ `ItemLoader`（info price/slotMax/tradeBlock + spec 效果 + 装备能力键预留）/`MobLoader`（info 攻防字段 maxHP/PADamage/PDDamage 等）；真实数据验证通过（红药水 2000000、蜗牛 100100 maxHP=8）
- [x] **WZ 文件缓存**（2026-08-06）：`WzCache` 解析结果 Java 序列化到磁盘（items.ser/mobs.ser），重开读缓存省重解析（秒级重开在 2C2G 上的关键）；读失败自动回退重解析；数据源变更 `clear()`
- [ ] WZ 解析余项：生命体完整字段（NPC 结构）、Equip 数据（北斗 WZ 当前 Equip 目录为空，需解包装备数据）
- [x] **数据源定位**（架构 6.4）：`twinkle.wz.path` 直接指定 WZ 目录（单份）；`application.yml` 已加配置；读不到报错的启动校验随装配落地

### 4. domain-script（GraalVM JS）

- [x] 脚本引擎接入（org.graalvm.js:js 独立库 + EnableJVMCI 全速，M0 已验证；2026-08 版本对齐 **js-language 23.1.11 ↔ JDK 21.0.11**，见 m0-dependency-versions 记忆）
- [x] **宿主对象契约**（2026-08-06）：`host` 包 5 接口 `Cm/Qm/Em/Rm/Im` 接口化（v83 脚本兼容命名，参数/返回用基本类型，脚本不触碰游戏对象具体类，红线 11/12）；`ScriptManager` 按接口自动注入（cm/qm/em/rm/im）
- [x] **数据源定位**（架构 6.4）：`twinkle.script.path` 直接指定脚本目录（单份）；`ScriptRepository` 递归扫描 `.js`，目录不存在构造即抛异常（启动校验，读不到即报错）
- [x] **L2 热更新**（架构 5.3）：`ScriptRepository.reload()` 按 mtime 判定增减改 → `ScriptManager.reload()` 统一入口；每次执行按 key 从当前快照取最新源码，reload 后新调用自动用新代码
- [x] **脚本重载不影响进行中的脚本**：快照不可变 Map，eval 持有调用时刻的字符串，reload 不破坏在跑脚本

> 2026-08-06 第四片交付：脚本引擎四件套（`host` 契约 + `ScriptSource`/`ScriptRepository`/`ScriptManager` + bootstrap ScriptConfig/ScriptInitializer 装配）。14 测试通过（ScriptEngine 5 + ScriptManager 5 + ScriptRepository 4）。脚本调用方（NPC/任务 handler）留 M2-5/M2-2 接入。

### 5. channel（频道初版）

- [x] 游戏 tick 单线程框架（core `org.gms.tick`：TickHandler/TickScheduler/GameTickLoop，tick 帧边界暂停 = 热重载换点干净，2026-08-06，6 测试）——放 core 因架构 core 含"调度"，且避免 replaceable→channel→domain-game 循环
- [x] **频道初版 + 玩家在线表**（2026-08-06）：`ChannelServer`（复用 V83ServerInitializer，独立 EventLoop）+ `PlayerStorage` 在线表 + `ChannelMapManager` 地图缓存 + bootstrap 装配（ChannelConfig/ChannelNetworkInitializer）；CC 迁移留 M4/M6
- [x] **玩家进图打通**（2026-08-06）：data↔domain 角色加载投影 `CharacterLoader`（data.Character 74 列 → domain.Character，Versioned）+ `PlayerLoggedinHandler`/`PlayerMapTransitionHandler` + `ChannelPacketFactory.getCharInfo`（SET_FIELD + addCharacterInfo 空数据版）+ `ChannelFlowE2ETest` 全链路字节级验证（登录→选角→连频道→PLAYER_LOGGEDIN→SET_FIELD→PLAYER_MAP_TRANSFER→在线表/地图持有角色断言）

> 2026-08-06 第三片交付：进图链路打通。关键结论——本 v83 分支（HeavenMS 系）无经典 OdinMS 的 getMapData 大包，地图静态数据（foothold/VR/传送点）由客户端本地 WZ 自取，服务端只发 SET_FIELD + 角色全量 + 动态对象，进图工程量因此大减（思路参考自 BeiDou-Server）。

### 6. parity 验证

- [x] **行为对齐（逻辑侧公式对照，2026-08-06）**：`ParityReferenceTest` 用参考项目公开公式作真值对照本实现——伤害（`calculateMaxBaseDamage`）、落点（`calculateFooting` 整数插值）逐例一致；顺手修正落点插值为参考整数公式（原浮点与参考差 1）
- [ ] 录包回放：参考项目录包 → 本实现回放 → 行为对齐（M0 基建就绪，**真实录包素材待 v83 客户端环境**）
- [ ] 双跑对照：同一输入跑参考项目与本实现，输出对齐（需参考项目运行环境）

## 验收标准

- [ ] 客户端登录、进图（协议链路 E2E 已验证：登录→选角→连频道→SET_FIELD→地图转移完成；**真实 v83 客户端接入待客户端环境**）
- [ ] 移动 / 战斗 / 交易 / 任务 / 背包行为对齐参考项目（parity 通过）——**逻辑侧已就绪（六个 system，M2-2），协议 handler 接入转 M3（见 M3 任务文档第 5 节）**
- [ ] 存档读写在 `newmaple` 兼容结构上正确（老存档可读）——data.Character 74 列映射 + 进图加载投影已落地；背包/技能存档表随 M2-2 落地
- [ ] 状态/逻辑分离架构测试通过（可替换层引用纪律）
- [ ] 脚本热重载生效（改 JS 即生效）

## 风险与注意

- **别"顺手改进"协议/存档**：v83、`newmaple`、存档格式三条兼容红线（1/2/3）在重写期最容易破，靠 parity 测试兜底。
- **交易等跨 tick 操作**天然是热重载风险点，M0 起按"单实体安全点"设计（5.3），此处落地为逻辑。
- **游戏对象不进容器**（红线 4）：重写期最容易退化成"顺手放进容器省事"。
- 管理侧仍**不得依赖 domain-game**（依赖单向无环）。
