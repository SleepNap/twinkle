---
name: m2-progress
description: M2（游戏逻辑重写）进图打通：SET_FIELD/addCharacterInfo 字节结构、数据映射坑、频道初版决策与待办
metadata:
  type: project
---

M2 第三片交付（2026-08-06）：**进图链路打通**（登录→选角→连频道服→PLAYER_LOGGEDIN→SET_FIELD→PLAYER_MAP_TRANSFER→在线表/地图持有角色）。`ChannelFlowE2ETest` 全链路字节级验证通过。全量 132 测试绿。

**关键认知（思路参考自 BeiDou-Server）**：本 v83 分支（HeavenMS 系）**没有**经典 OdinMS 的 `getMapData` 大包——地图静态数据（foothold/VR/传送点位置）由**客户端本地 WZ 自取**，服务端进图只发 SET_FIELD + 角色全量 + 动态对象 spawn。进图工程量因此大减。

**SET_FIELD（getCharInfo）字节结构**（`ChannelPacketFactory.charInfo`，空背包/技能版）：
`SET_FIELD(0x7D) + channel-1(int) + 1 + 1 + short 0 + 3×random int + addCharacterInfo + filetime(now)`。
`addCharacterInfo`：`-1(long) + 0 + charStats + buddyCapacity + linkedName(0) + meso + inventory + skill + quest + minigame(short0) + crushRings(short0) + friendshipRings(short0) + teleport(5+10×int0) + monsterbook(int cover+byte0+short0) + newyear(short0) + areainfo(short0) + short 0`。
**addInventoryInfo 分隔符宽度差异（v83 固有）**：equipped 结束 `short 0`、equip-cash 结束 `short 0`、equip-背包结束 `int 0`、use/setup/etc 结束各 `byte 0`、cash 无结束标记。5×slotLimit(equip/use/setup/etc/cash=100) + `long filetime(-2)` 开头。
**filetime getTime**：`-1→DEFAULT_TIME、-2→ZERO_TIME、-3→PERMANENT`；否则 `utc*10000 + FT_UT_OFFSET`（含 `TimeZone.getDefault().getOffset`，字节级关键）。

**数据/装配坑**（后续沿用）：
- **MyBatis-Flex 默认驼峰→下划线列名**：`hpMpUsed` → `hp_mp_used`，与 DB 驼峰列不符，**所有驼峰新增字段必须 @Column 显式标注**（全小写字段如 guildid 无需）。
- **insertSelective 对基本类型插 0 覆盖 DB DEFAULT**：buddyCapacity/equipSlots 等不显式设会插 0（V2 DEFAULT 25/24 被覆盖），建号/测试需显式设 v83 默认值。
- **实现类不加 @Singleton 若同时有 Factory @Bean**：ChannelHandlerRegistrar 双份歧义（NonUniqueBeanException），同 m1-progress 的 Flex*Repository 教训。
- **LoginHandlerRegistrar.register 签名扩为 `(registry, serverName, channelIp, channelPort)`**：频道地址经注入，E2E 用随机端口。
- **CharacterLoader 投影**（channel 模块）：`data.Character 74 列 → domain.Character`（构造取 `VersionGate.currentVersion()`）；boolean 字段 DB 0/1 转换；Lombok 对 boolean 生成 `isXxx()`。
- 频道服/登录服共享同一 HandlerRegistry（opcode 不冲突），各自独立 EventLoop（红线 4）；单进程默认登录 8484 / 频道 8584（错开）。

**M2-4 脚本引擎（第四片，2026-08-06）**：`host` 包 5 契约接口 `Cm/Qm/Em/Rm/Im`（v83 脚本兼容命名，基本类型参数/返回，脚本不触碰游戏对象具体类）+ `ScriptSource`/`ScriptRepository`（递归扫 `*.js`，key=相对路径去扩展名，目录不存在构造即抛异常=启动校验）+ `ScriptManager`（每次执行取当前不可变快照源码，reload 不影响进行中）+ bootstrap `ScriptConfig`/`ScriptInitializer` 装配。14 测试绿。**坑**：Micronaut 4 `@Bean` 销毁用 `preDestroy` 属性（无 `destroyMethod`）；BootstrapContextTest 须传 `twinkle.script.path`（默认 `./scripts` 不存在会启动失败）；宿主对象必须 public 类+public 方法。

**M2-3 WZ 余项 + M2-2 背包/战斗（第五片，2026-08-06）**：`ItemData`/`MobData` 纯数据（domain-game item/mob 包，Serializable）+ `ItemLoader`（info/spec + 装备能力键预留，北斗 Equip 目录当前空）/`MobLoader`（info 攻防字段）+ `WzCache`（解析结果 Java 序列化磁盘缓存，重开省重解析，读失败回退重解析）；`CharacterState`（spi）扩展背包接口 addItem/getItemCount/removeItem + `replaceable.ItemSystem`（give/take/count，版本门）；`MapleMonster` 运行时怪物 + `replaceable.DamageCalculator`/`CombatSystem`（v83 物理公式 `ceil((weaponMult×主+副)/100×watk)`，思路参考北斗）。**关键**：ArchUnit 规则 3 只禁 replaceable 依赖 domain.game/inventory/skill/data.entity——**item/mob/map 包（ItemData/MobData/MapleMap）可被 replaceable 依赖**，Inventory/Item 只能经 spi 接口操作。

**M2 核心机制全部完成（第六片，2026-08-06）**：移动（CharacterState 位置 x/y + `MapleMap.groundBelow` foothold 落点插值 + `MovementSystem`）、交易（`Trade`/`TradeSide` 状态机 ACTIVE→锁定→DONE/CANCELLED + `TradeSystem` 物品+meso 双向结算）、任务（`QuestStatus` 状态机 + CharacterState 任务接口 + `QuestSystem`）。六个 system（HealthRecovery/ItemSystem/CombatSystem/MovementSystem/TradeSystem/QuestSystem）统一模式：**经 CharacterState spi 接口操作 + 版本门 + 逻辑无状态化**。协议层接入（移动包 PLAYER_MOVE/攻击包/交易包/NPC 任务脚本挂接）留 M2-5/M3 网络 handler；任务存档表留后续。

**M2-6 parity 逻辑对照（2026-08-06）**：`ParityReferenceTest` 用参考项目公开公式作真值对照本实现——伤害（`calculateMaxBaseDamage`）、落点（`calculateFooting`）逐例一致；**顺手修正 `MapleMap.interpolateY` 为参考整数插值**（原浮点实现与参考差 1，parity 值级不一致的隐患）。

**M2 剩余**：parity 真实录包回放/双跑对照（需 v83 客户端素材 + 参考项目运行环境）、生命体 NPC 结构、Equip 数据（北斗 WZ Equip 目录空）、真实 v83 客户端进图验收。协议 handler 接入（六个 system 的网络层）转 M3（见 M3 任务文档第 5 节）。

相关：[[twinkle-project-context]] [[m1-progress]]
