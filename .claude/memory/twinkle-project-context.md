---
name: twinkle-project-context
description: twinkle 项目背景（MapleStory v83 后台，架构定稿于 ARCHITECTURE.md）
metadata:
  type: project
---

twinkle 是**热更新、扩展性好的冒险岛后台**（MapleStory v83 服务端），MIT 协议。权威规范是 `ARCHITECTURE.md`（设计决策、模块划分、运行拓扑均以该文档为准），M0-M6 实施任务拆在 `docs/archived/tasks/`。

**进度（2026-08-06）**：M0 骨架与基础设施完成；**M1（协议+Netty+登录）核心完成**（net-packet 协议层、data MyBatis-Flex、net-netty、login、bootstrap 装配、parity 录包回放），全链路 E2E 验证通过。M1 剩真实 v83 客户端接入验收。**M2（游戏逻辑重写）进图打通 + 脚本引擎 + WZ 数据 + 核心机制全部完成 + parity 逻辑对照**：进图链路（WZ foothold + data↔domain 投影 + 频道初版 + SET_FIELD 包 + E2E）；脚本引擎（host 契约 + ScriptManager + L2 热重载）；Item.wz/Mob.wz + WzCache；核心机制六件套（背包/战斗/移动/交易/任务/生命恢复）统一 CharacterState spi 接口 + 版本门；parity 逻辑公式对照（伤害/落点 vs 参考项目）。M2 剩 parity 真实录包回放（需客户端素材）。

**M3 全完成（2026-08-08）**：HTTP + AI + 按实体渐进重载三块 + M3-5 协议层接入全落地（此前暂缓解除）。- **M3-1 http-api**：`/internal/v1/*`（健康/在线/reload）+ `/api/v1/*`（Bucket4j 限流 + 版本化）。数据三路：①查 DB（data repository）②经 `AdminService`（**core 公共契约**，放 core `org.gms.service.admin`——分进程 RPC 桩换实现接口不变，避免 http-api 依赖 channel/domain-game）③事件快照镜像（`ChannelEventPublisher` 推 `OnlinePlayerEvents` → `OnlinePlayerMirror` 只读镜像，**单向只读禁止回写**）。限流参数经 Micronaut `@Property`（`twinkle.http.api.rate-limit.*`，非 ConfigFacade）。可观测挂接 `ObservabilityConfig`（NoopMetrics/MemoryHealthRegistry 装配 bean）。`MyBatisFlexFactory` 补齐 InventoryItem/Quest/AiUsage 七 Mapper 注册。
- **M3-2 ai**：**模型自研** `LocalRuleChatModel`（实现 `ChatModel`+`StreamingChatModel`，本地规则路由代替外部 LLM API——2C2G 红线 + 无 key），Agent/工具调用循环/流式/结构化输出全走真实 LangChain4j API（AiServices + @Tool + TokenStream + POJO），换真实 LLM 只换 ChatModel bean。工具 `GameStatTool` 经 `AdminService` 取数。计费落 `ai_usage` 表（V4 迁移）。每日总结 `AiDailySummaryScheduler`（Metrics 埋点+生命周期）。客户端接口 `/api/v1/ai/chat|report/online|usage`。
- **M3-3 按实体渐进重载**：`EntityReloadCoordinator`（core，按实体跟踪在途操作/safeOnly）+ `EntityReloadService`（逐实体推进：安全点直切/在途显式中断 + `VersionGate.onReload()` 换代，绝不做全服同步原子重载）。`TradeSystem.interrupt`（显式中断+回滚，背包归位玩家看到"交易被取消"）。`PlayerInteractionHandler` 接入 begin/endOperation。运维端点 `GET /internal/v1/reload/in-flight` + `POST /internal/v1/reload`。
- 全模块测试全绿（18 bootstrap 例 + 各模块）。M3 验收标准 4 项全勾。

**M3（HTTP+AI+渐进重载）第 5 节协议层接入完成（2026-08-06）**：进图后可玩闭环全部落地——前置缺件补齐（`PlayerSessionRegistry` 角色↔会话 + 广播、bootstrap `WzConfig` 装配六个 replaceable system + Item/Mob 数据、`MapleMap` 怪物容器 + objectId、`Character.mapObject`、断链注销 `DisconnectListener`）；`MovePlayerHandler`（v83 移动包解析）+ `AttackHandler`（近战/远程/魔法）+ `MonsterSpawnService`（刷怪/重生）+ `PlayerInteractionHandler`（交易，走 PLAYER_INTERACTION 子动作）+ `UseItemHandler`（物品使用）+ NPC 对话双路由（`NpcTalkHandler`/`NpcTalkMoreHandler`，经典 status 重入 + 北斗 nextlevel 函数派发，`ConversationScript` 每会话独立 GraalVM Context）+ V3 存档表（`queststatus`/`questprogress`/`inventoryitems` + 实体/仓库）。E2E 全绿：`GamePlayE2ETest`/`NpcDialogE2ETest`/`TradeE2ETest`。掉落表（mob drops）与魔法伤害公式留后续。

**时序决策（2026-08-07，已执行完毕）**：M3 剩余三块（第 1 节 http-api `/internal`+`/api`、第 2 节 LangChain4j AI、第 3 节渐进重载 L3）曾被暂缓等前端对接；**2026-08-08 用户明示解除暂缓，已全部按序完成**（见上文 M3 全完成）。后续 M4 起不再有此暂缓约束。前端 twinkle-web 被整体删除（暂存删除未提交），重启前保持现状。

**任务/流程/子线程可观测口子（2026-08-07 用户要求）**：做 Web 管理时要能**清晰管理/监控各类任务、流程、子线程**——此前是隐式的、看不到具体发生了什么。已存在的隐式调度器：`GameTickLoop`（core tick 线程）、`MonsterSpawnService.respawnScheduler`（channel 怪物重生，独立 ScheduledExecutor）。可观测地基已就绪但未挂钩：core `observability`（`HealthRegistry`/`Metrics`/`MdcKeys`/`Sli`）。**How to apply**：做 web/M5 时给所有调度器/任务加监控钩子（生命周期、运行数、最近一次执行、异常计数），经 `Metrics`/`Health` 暴露；新写的调度器（如 MonsterSpawnService 已落地）就应带可观测口子，不重蹈隐式覆辙。

**评审跟进（2026-08-06，见 `docs/archived/architecture-review.md` 决策记录）**：二轮评审采纳 A 组落地——R3 版本门前移（core `hotreload.versioned`）、R4/R5/R15/R14 文档、R10 可观测性地基（core `observability`：Metrics/Health/MdcKeys，HTTP 绑定留 M3）、安全门槛（gitignore、`SqlInjectionScanTest`、注入 demo）。C1 已清（CLAUDE.md 无 Flyway）。推迟项见评审决策记录。

**How to apply:** 改动前先读 `ARCHITECTURE.md`；按 `docs/archived/tasks/README.md` 顺序推进 M0→M6。关键红线：v83 协议字节级兼容、`newmaple` 库兼容、2C2G 强制单进程、状态与逻辑分离、可替换层不得引用稳定层具体类。参考兄弟项目时遵守 [[reference-projects-discipline]]；M1 的字节级验证方式与关键坑见 [[m1-progress]]；M2 进图字节结构（SET_FIELD/addCharacterInfo）与数据映射坑见 [[m2-progress]]。所有文档/注释用中文。

**M4 全完成（2026-08-09）**：插件系统 + 热更新 L1-L4 全通 + 频道间三机制 + 可靠性三件套 + 配置中心形态五块全落地。
- **M4-1 插件系统（VSCode 式）**：`plugin-api` 纯 SPI（`org.gms.plugin`：SdkVersion/Plugin/PluginContext/PluginDescriptor/ContributionType 7 类/PluginHost）+ core 插件运行时（`org.gms.plugin.runtime`：PluginManager/PluginClassLoader/ManifestPluginDescriptorParser/PluginScanner）+ `LogicSystemRegistry`（core hotreload）。**关键设计**：插件 classloader = `org.gms.*` 一律父优先（结构上不可能遮蔽稳定层类防 CCE）；manifest 声明式注册（`META-INF/twinkle-plugin.properties`）；贡献点版本化（红线 13，reload 用 `max(声明版本, versionGate.currentVersion())` 保单调）；bootstrap `TwinklePluginHost` 接线 5 类贡献点（packet/tick/event/script-namespace/logic-system），AI Tool + HTTP 端点声明不接线（M4 决策，M5 随管理进程插件宿主）。插件 reload = L3（unload + 版本门换代 + 渐进重载）。
- **M4-2 热更新 L1-L4 全通**：L4 数据面补齐——`CharacterRepository.save` + `Character` transient dirty 标记（持久化 mutator 手写覆盖 Lombok）+ `CharacterLoader.toData` 反向投影 + `CharacterSaveQueue`（单写执行器 db-writer + 按角色去重 + `flushAllSync`）+ `RestartService`（DRAINING=tick pause+中断在途+drain；FLUSH_DIRTY=flushAllSync 只刷脏）。断链注销缺口补齐（playerStorage.remove + map.removeCharacter + saveQueue.save）。**坑**：drain 不能 shutdown 执行器（FLUSH_DIRTY 还要提交）；flushAllSync 同步落库确保重启前落盘。L1 管理端点 `POST /internal/v1/config`；L2 脚本热重载 + 插件命名空间共存。
- **M4-3 频道间三机制**：`IntercoordService` 接口放 core（`org.gms.service.intercoord`，channel 与 coordinator 都依赖 core 不互相依赖，同 AdminService 范式）+ coordinator 实现（`LocationTable`/`ChannelRegistry`/`SingleOwnerStore`，进程内真值）。消息 payload 放 core `org.gms.message`。`WhisperHandler`（定位表查目标频道→总线投递）/`ChangeChannelHandler`（CC 迁移一机制两用）/`BuddyHandler`（buddylist V6 表单一属主持久化）/`ChannelMessageSubscriber`（订阅 channel:{id} 目标投递悄悄话+公告广播）/`ChannelLocationBinder`（进图/下线事件→定位表）。**范围诚实标注**：公会/排行完整玩法不在 M4，M4 落"单一属主存储"基础设施 + 好友/公告/商店资金各一条验证。
- **M4-4 可靠性三件套**：`ReliableEventBus`（core `org.gms.event`）——发送先落 outbox（bus_outbox V5 表）→ 投递 → 标记；接收侧按 stream 严格 seq+1 按序投递（越序/重复丢弃）+ messageId 幂等去重 = 恰好一次。CC 迁移走可靠总线（stream=`cc:player:{id}`）。**坑**：严格按序须 `seq == last+1`（仅 <= 判定会让越序 seq=3 漏进）；`PayloadCodec` 接口化（M4 用 MARKER，M6 换 JSON）。
- **M4-5 配置中心形态**：DB 真值 + 版本号广播链路全验证（ConfigCenterE2ETest：admin 写→DB→版本+1→广播→订阅者重读）。单进程"TCP 长连接订阅"= 进程内 EventBus 订阅，接口按 4.6.5 设计，M6 换网络。
- 迁移 V5__bus_outbox.sql + V6__buddylist.sql；全量测试 29+ 全绿。

**M4 完成范围诚实标注（后续开发必读，详见 docs/archived/tasks/M4-plugin-hotreload-channels.md"完成范围诚实标注"节）**：
- 插件 HTTP 路由 / AI Tool 贡献点：**未接线**（M5 随管理进程插件宿主做）。
- 加好友：`buddyListPacket` 是简化版（数量写死 0，未读真实 DB 列表回填）；v83 BUDDYLIST 包逐字节布局未核对。
- 喇叭：**只有活动公告广播**，喇叭道具 handler（点喇叭消耗道具）未做。
- CC 迁移：单进程内"重连"= 同进程；真正跨频道端口重连是 M6。
- 公会/排行完整玩法不在 M4（M5 配套 Web 控制台）。
- 可靠总线序列化：PayloadCodec.MARKER（单进程投递真实对象）；跨进程 JSON + 接收侧去重落 bus_stream 表是 M6。
- 技术债：`TradeE2ETest` 偶发失败（`Thread.sleep(100)` 计时脆弱，非功能回归，未修复）。

**M5 完成（后端面，2026-08-09）**：Web 控制台**后端运维 API** + 单库迁移 + 上线切换三块落地（前端页面**未做**——用户决策"前端形态尚未设计"）。
- **M5-1 admin 后端运维 API**：`AdminConsoleController`（http-api 模块，路由 `/admin/v1`）——health/channels/online/reload-in-flight/config GET+POST/config{key}/kick/reload/logic/reload/scripts/restart/restart-phase 十二端点。数据三路沿用 M3：频道状态②经 `IntercoordService.channels()`（新增），在线玩家③只读镜像，配置①`DbConfigFacade`。运维操作全经 service 接口/core 契约：`AdminService` 扩展 `reloadScripts()/requestRestart()/restartPhase()`（channel 侧 `ChannelAdminService` 委托 ScriptManager/RestartService），管理侧不 import ScriptManager/RestartService 具体类。
- **关键补齐**：`ChannelRegistryRegistrar`（bootstrap @Context 装配）——此前生产无人调 `registerChannel`，注册表恒空，`/admin/v1/channels` 空列表。构造期上报频道（host 读 `twinkle.net.channel.host` 默认 127.0.0.1，port 读 `twinkle.net.channel.port`）。**这是频道状态列表可用的前提。**
- **重启测试安全**：`twinkle.admin.restart.exit=false` 供测试（只编排不真退出）；生产默认 true（编排完 System.exit，外部启动脚本拉起）。进程边界是配置（铁律 1）。
- **M5-2 单库迁移**：`V7__queststatus_newmaple_columns.sql` 补 queststatus 四列（expires/forfeited/completed/info，newmaple 有 twinkle V3 无；每列一条独立 ALTER，红线 2）。`QuestStatusEntity` 加四字段（domain 层不改）。`NewMapleImporter`（data `org.gms.data.tools`，复制引擎，显式列清单+显式主键保 ID 外键）+ `NewMapleImportMain`（bootstrap CLI，`--reset-passwords` 默认把非 BCrypt 老哈希重置为 changeMe123!）。**坑**：COALESCE 表达式列名在 SQLite 不保留原名，`rs.getObject` 必须按索引取。跳过 buddies（与 twinkle buddylist 结构不兼容）。
- **M5-3 上线切换**：`micronaut.server.host` 默认 127.0.0.1（红线 20 网络平面收敛）；`LoggingDisciplineTest`（静态扫描 src/main/java 禁 printStackTrace/System.out/System.err，CLI 工具包白名单）；`scripts/start.sh`/`migrate-newmaple.sh`/`rollback.sh` + `docs/archived/ops/switch-to-production.md`（profile 清单/迁移/切换/回滚/CC 兜底/已知差异）。
- **测试**：全量 `mvn -B verify` 17 模块 SUCCESS，bootstrap 33 测试全绿。新增 AdminConsoleE2ETest/MigrationImportE2ETest/LoggingDisciplineTest/NewMapleImporterTest/V7QueststatusColumnsMigrationTest + ChannelAdminServiceTest 扩展。
- **完成范围诚实标注**：①前端页面未做（留后续，API 不搬家）②`/admin/v1` 无鉴权仅靠 loopback 兜底（强鉴权留后续安全里程碑）③老库密码不保真（reset 为默认口令）④好友列表不迁移（buddies 跳过）⑤queststatus 四列领域层不使用默认 0。

**幽灵玩家事故报告阶段 B 完成（2026-08-09）**：`docs/archived/ghost-player-monster-controller-incident.md` 的 Twinkle 稳定层补强五项全部落地（报告 §5.1 缺口属实）。核心是**三块稳定层设计**：
- **分阶段心跳**（net-netty）：`HeartbeatGuard` 状态机 readerIdle→PING(seq)→PONG deadline，替代无效的 allIdle（原 allIdle 会被服务端发包重置永不触发）；PONG 在 HandlerRegistry 分发前拦截（各阶段共享稳定贡献点）；PROBING 中任意收包视为传输响应（防探测期恢复发包假阳性）；单调时钟。**坑**：IdleStateHandler 需毫秒级配置（用 TimeUnit.MILLISECONDS 构造），测试才能驱动小值。
- **会话代际**：`NetworkSession` 不可变 `sessionId`（进 PacketSession 接口，全部 handler 可读）；`PlayerSessionRegistry` 改 Entry(sessionId,generation) + `claim`/`unregister` compare-and-remove（旧代际迟到关闭只记 `supersededCleanupRejected` 计数，不误删新会话）；`PlayerStorage.remove` 身份比较；断链回调 unregister 失败即短路（**防旧态覆盖新会话 DB**——不仅是"不删登记"）。重复登录 `PlayerLoggedinHandler` 认领前先移除地图/在线表同 id 旧角色。
- **怪物控制租约**：`ControllerLeaseService` 放 **domain-game**（不是 core——core 的 service.* 是跨进程契约，租约只在频道进程内；贴近稳定层状态）。按 LeaseOwner(characterId,sessionId,generation) 归属、受控活怪按 owner 聚合（报告 §4.4）、`renew` fail-closed（任一校验不过丢弃整包防伪造续租）、仅 LEASE_EXPIRED 进冷却、计数归零即 IDLE 不处罚、onClaim 立即 SESSION_REPLACED 释放旧代际。巡检 = `DefaultControllerLeaseService implements TickHandler` 挂 GameTickLoop（**不新增线程**，tick 暂停自动停扫、恢复后 gap>3× 加宽限）。控制分配：`SPAWN_MONSTER_CONTROL(0xEE)`/`MOVE_MONSTER(0xEF)`/`MOVE_MONSTER_RESPONSE(0xF0)` + `MoveLifeHandler`；`ensureSpawned`（按 mobId 去重，**修复了 spawnForMap 每玩家进图怪物翻倍 bug**）+ `onPlayerEnter`（进图玩家刷怪包唯一通道）+ `reassign`（周期无主怪接管）。
- **测试**：全量 `mvn -B verify` 17 模块 SUCCESS，bootstrap 38 测试全绿（此前 33）。新增 HeartbeatE2ETest/SessionIdentityE2ETest/MonsterLeaseE2ETest + HeartbeatGuardTest/PlayerSessionRegistryTest/DefaultControllerLeaseServiceTest。完成标准映射：标准 1/2→MonsterLeaseE2E、3→SessionIdentityE2E、4→HeartbeatE2E、5→租约单测 tickPause 用例。
- **顺手修复 M5 遗留**：LoginServiceTest 桩缺 `save`、AI 两处 FakeAdmin 缺 `reloadScripts/requestRestart/restartPhase`（M5 加接口未同步测试桩）。
- **parity 遗留（待真机）**：`SPAWN_MONSTER_CONTROL` 控制位 1 vs 5、`MOVE_LIFE`/`MOVE_MONSTER`/`MOVE_MONSTER_RESPONSE` 字节布局基于对 BeiDou 的理解，代码注释标注"待 parity 录包回放核对"。

**M6 分布式完成（阶段 A/B/C，2026-08-09）**：多机部署、玩家换频道、升级滚动三块落地（单机多进程验证，真实多机留运维）。核心是**三块设计**：
- **内部通信网络总线**（net-netty `internal` 包）：帧 = `[magic 2B | type 1B | messageId 8B | payloadLen 4B | payload]`；`InternalServer`（coordinator 监听）/`InternalClient`（频道连接，**断线重连**，修复了重连失败后 `reconnectScheduled` 不重置只重试一次的 bug——doConnect 开始时重置）/`InternalConnection`（帧读写 + RPC 响应匹配 + RPC 请求/响应双路由）。`CoordinatorLink` 封装连接生命周期（重连重挂帧处理器 + 重报 REGISTER）。**coordinator 端路由在 net-netty**（`CoordinatorFrameRouter`/`ChannelConnectionRegistry`/`IntercoordRpcDispatcher`/`AdminRpcDispatcher`）——经 core 接口操作进程内真值，遵守架构规则 4（coordinator 模块不依赖 net-netty）。
- **split 装配**：`twinkle.role`（coordinator/channel）+ 4 个条件类（core `org.gms.role`）+ `SplitConfig` 互斥装配。single 档语义完全保留（39 测试兜底）。**坑**：`InternalServer`/`ChannelChangeReceiver` 须 `@Context` 强制装配（懒 @Singleton 无人引用不实例化）。`SharedServiceConfig` 拆出 VersionGate/EntityReload（管理进程 http-api 依赖）。`DataLayerInitializer` 改 @Context（split 频道进程无 HTTP 也初始化 DB）。
- **可靠总线恰好一次**（CC 迁移正确性核心）：`JsonPayloadCodec`（真实序列化 record）+ `ReliableDelivery` 接口（序号随 EVENT 帧携带）+ `ReliableReceiver`（接收侧按 **bus_stream 表** `lastDeliveredSeq` 判序去重 + 越序暂存等待前序 + ack 闭环：应用后 `advanceLastDeliveredSeq` + `markAcked`）。M4 的 ReliableEventBus 是发送侧重投去重 + 标记 DELIVERED。**单机多进程共享 DB**，接收侧 ack 直接写同一 outbox/bus_stream；跨机需网络 ack 帧（M6 诚实标注）。
- **CC 迁移跨进程**：`ChangeChannelHandler` 发送前 `flushCharacterSync` 同步存档（玩家状态落 DB）；目标频道 `ChannelChangeReceiver`（@Context）订阅 CC 请求，ReliableReceiver 恰好一次 + 定位表 movePlayer。
- **测试**：全量 `mvn -B verify` 17 模块 SUCCESS，bootstrap 39 测试全绿（含 `SplitChannelE2ETest` 两个用例：coordinator+2 channel 跨进程悄悄话/定位/CC 迁移 + coordinator 无状态重启注册表重建）。新增 core `ReliableReceiverTest` 4 例。
- **剩余项（诚实标注，见 M6 任务文档）**：配置中心 TCP 长连接推送未做（M4 进程内 EventBus 订阅已验证）；CC 客户端真机 loading 界面留客户端验收；真实多机/网络分区演练留运维；coordinator 心跳超时标记下线未做（依赖断链检测触发重连）；喇叭/加好友生产接收端同 M4 标注。

**数据库规范重写（2026-08-09，覆盖上文中 newmaple 内容）**：用户明确"全新自研，不兼容 newmaple/北斗"。数据库全面重写：
- **命名规范**（详见 [[db-naming-migration-standard]]）：表名 ≥2 词、字段 snake_case、禁关键字（`int`→`int_stat`）。
- **迁移目录**：`db/migrate/{common,sqlite,postgresql,mysql}/`，禁 `-- dialect:` 节（MigrationRunner 改目录扫描，方言差异靠目录）。common 放 seed（V7），方言目录各 V1-V6 DDL。
- **表名全改**：`param_config`/`account_records`/`character_records`/`quest_status`（九列一次建全，含原 V7 四列）/`quest_progress`/`inventory_items`/`ai_usage_log`/`bus_outbox_queue`/`bus_stream_state`/`buddy_list`。
- **实体改名**：10 个实体 `@Table` 新表名 + 字段 snake_case（MyBatis-Flex 驼峰→下划线自动匹配，无需 @Column）。`CharacterLoader`/`LoginPacketFactory`/各 repo/测试同步 getter/setter。
- **删除 newmaple**：`NewMapleImporter`/`NewMapleImportMain`/`MigrationImportE2ETest`/`NewMapleImporterTest`/`V7QueststatusColumnsMigrationTest`/`migrate-newmaple.sh` 全删；CLAUDE.md 红线 2 改为"表结构由自研迁移器管理"。
- **坑**：`data/` 运行时目录 gitignore；`ChannelServer`/`LoginServer` bean 加 preDestroy=close（多 context 测试端口不残留）；旧库直接删除重建。全量 `mvn -B verify` 通过，bootstrap 39 测试绿。

**积分计费系统 + 前端重构完成（2026-08-13）**：账号层积分额度 + 模型倍率 + plan 三档限额 + 充值/签到 + 联网搜索 + API Key 批量，配套文档归档与菜单分组。
- **计费模型**：积分挂 `account_records.id`（账号层共享）；AI 调用扣 `ceil(inputTokens×inputRate + outputTokens×outputRate)/1e4`，`model_rate` 按模型倍率（local-rule 倍率 0 免费）；`subscription_plan` 月/周/5h 三档滚动限额（limit>0 生效，0=不限制），无 plan 纯余额扣；`*` scope 管理员 key 免计费，普通 key 无 `ownerAccountId` 调 AI 拒绝。
- **数据层**：V12__point_billing.sql（三方言）+ V13__point_billing_seed.sql（common）——`point_account`/`model_rate`/`subscription_plan`/`point_transaction` 四表 + `ai_usage_log` 补 model/input_tokens/output_tokens/points_cost/account_id 五列；实体/Repo/Mapper 全套 + `MyBatisFlexFactory` 注册（18 Mapper）。
- **计费服务**：`BillingService`（http-api `org.gms.httpapi.billing`）precheck/charge/adjust/signin/purchase/balance/setPlan——调用前 precheck（超额 429），调用后 charge（token+websearch 扣分，扣费失败不阻断已返回结果）。**计费点位置见下方 2026-08-19 修正**（原挂在 `ToolExecutionService.investigateWithAgent`，已下沉）。
- **联网搜索**：`WebSearchTool`（ai `@Tool(name="web_search")`，接 Tavily REST，`twinkle.ai.websearch.provider=off|tavily`，off 不注册进 tools；按次扣 `twinkle.billing.websearch.cost` 默认 1 积分）。
- **管理 API**：`BillingAdminController`（/admin/v1/billing/*：账号额度/调账/设 plan/流水/plan CRUD/倍率 CRUD）+ `AccountAdminController`（/admin/v1/accounts 账号搜索）+ `AccountRepository.findById/findByNameLike`。
- **API Key**：签发 dialog 改账号搜索多选（批量签发，1 key : 1 账号，`IssuedKey` 加 ownerAccountId）；列表 checkbox 批量禁用/吊销；余额列（读 billing 账号额度）。
- **前端**：菜单可折叠分组（监控/玩家数据/系统运维/安全凭据）+ 新增积分额度页 `/billing`；文档统一归档到 `docs/{planned,in-progress,archived}/`。
- **坑**：Micronaut annotation processor 增量编译残留旧 `$MyBatisFlexFactory$XxxRepositoryN$Definition` 类 → NonUniqueBeanException，`mvn clean verify` 解决；改 `AccountRepository` 接口须同步测试桩（LoginServiceTest/AiFacadeBillingTest/AiAgentTest）。
- **范围诚实标注**：点券/金币/签约为抽象调账（落流水+每日限，不连真实 nx_credit/meso）；plan/倍率前端仅展示+后端 upsert 端点，前端编辑交互留后续。

**后端 log/exception 全量 i18n 迁移（2026-08-13）**：存量 Java 日志/异常/游戏内提示中文硬编码全部迁入 i18n key（约 300 处，跨 12 模块），`en-US` 配置下后台文案全英文。
- **基础设施**：静态门面 `org.gms.i18n.I18n`（delegate 模式，bootstrap `I18nInitializer` @Context 启动注入）+ `I18nService`（@Singleton，ResourceBundle，UTF-8 properties）。无法 DI 的场景（游戏对象/静态工具/手动 new 的 handler）走静态门面，可注入的走构造器注入，二者同一实例。
- **key 前缀约定**：`log.*`（日志模板，`{}` 占位，`I18n.message(key)` 无参返回模板，参数交 log4j）/ `error.*`（异常/校验，`{0}` 占位，有参插值）/ `game.*`（游戏内玩家提示，发 GBK，`{0}`）。日志 `{}` 与异常 `{0}` 不得混用。
- **编码规则（务必记住）**：语言 `zh-CN`→中文 WZ/script→中文客户端（GBK 多字节）；语言 `en-US`→英文 WZ/script→英文客户端（ASCII）。协议编码器固定 GBK（`InPacket.DEFAULT_CHARSET`，GBK 是 ASCII 超集，英文用 GBK 编=ASCII 字节），`twinkle.service.language` 只改文案内容不改编码；HTTP 固定 UTF-8。开源后国外玩家换英文 WZ/script + en-US 即可玩。
- **游戏内提示受控**：交易取消、踢人原因等经 GBK 协议发玩家的文案也走 i18n key（game.*），受 `twinkle.service.language` 控制（区别于 WZ/script 不受控）。

**Web 控制台强鉴权 + RBAC + 不可抵赖审计（安全顺序 1/2/3，2026-08-18）**：`/admin/v1` 从零鉴权（仅 loopback 兜底）升级为完整鉴权面。
- **强鉴权**：`AdminAuthFilter`（http-api `org.gms.httpapi.admin`，`@Filter("/admin/v1/**")`）认证 → RBAC 授权 → 写操作 reason 强制 → 审计。管理员 = `account_records` 账号（复用游戏登录 BCrypt 密码），登录发 DB session token（`admin_session`，SHA-256 摘要 + 恒定时间比较，默认 24h 可配）。
- **RBAC 可配置角色表**：`admin_role`（permissions 逗号分隔，`*` 通配）+ `account_admin_role` 关联；`AdminPermission` 9 权限点（`admin:read` 统一读 + 8 写细分）；内置 super_admin/operator/auditor。`AdminRoleController`（角色 CRUD + 账号角色分配）+ bootstrap 首个管理员（`TWINKLE_ADMIN_BOOTSTRAP_ACCOUNT/PASSWORD`）。
- **不可抵赖审计**：`admin_operation_audit` 表；写操作强制 `X-Admin-Reason`（缺 reason 400 前置拒绝），记录操作者 accountName + reason + 结果 + requestId。
- **前端**：登录页 + `AdminAuthProvider` 会话 + 路由守卫 + 退出；`admin.ts`/`billing.ts` 自动带 Bearer，401 跳登录；`ConfirmationDialog` 加 reason 输入；`roles-page`（角色 CRUD + 账号搜索分配）。
- **迁移**：V15__admin_rbac / V16__admin_session / V17__admin_operation_audit / V18__admin_rbac_seed（`AccountRepository` 加 insert，测试桩 LoginServiceTest/AiAgentTest/AiFacadeBillingTest 同步）。
- **诚实标注**：审计 best-effort + 强日志（非 fail-closed，管理操作非 DB 事务无法原子）；before/after 摘要字段建表未填充；`/internal/v1` 不加鉴权；管理员密码与游戏密码同源；session 集群失效广播未做。

**AI 计费两处存量缺陷修复（2026-08-19）**：4.2「AI 权限与预算」开工前的止血，两个缺陷都已在漏。
- **缺陷 1 模型 key 口径不匹配**：`charge` 传的是 `AiModelBundle.descriptor()`（`provider/modelName`，如 `deepseek/deepseek-chat`），而 `model_rate.model_key` 的 V13 seed 是裸 `deepseek-chat`；`computePoints` 的 `findByModelKey` 查不到就 `return 0` → **外部模型一路静默免费**。`local-rule` 同样匹配不上，但其 rate 本就是 0，结果碰巧正确，所以本地开发永远看不出问题。修复：`V19__model_rate_descriptor_fix.sql`（common，UPDATE 既有行，不改已冻结的 V13）。**local-rule 的 descriptor 是 `local-rule/deterministic`**（`AiModelFactory` 把 modelName 硬编码为 `deterministic`），不是 `local-rule/local-rule`。
- **缺陷 2 `/api/v1/ai/chat` 完全不计费**：`AiController.chat` 直接调 `AiFacade.investigate`，不经过能力面，既不 precheck 也不 charge —— 持 `ai:use` 的 key 走这条即无限免费。游戏内 `@gm` 是第三条同样漏的入口。
- **修复形态（重要，后续改动别踩回去）**：计费下沉为 core 契约 `org.gms.service.agent.AiGovernanceService`（precheck/settle/GovernanceTicket）+ `AiGovernanceException` + `NoopAiGovernanceService` 兜底（bootstrap `AgentBridgeConfig` 按 `@Requires(missingBeans)` 装配）；实现 `BillingAiGovernance` 在 http-api。**唯一计费点 = `AiFacade.investigate` 内部**，三条入口自动全覆盖；**`ToolExecutionService` 的 precheck/charge 已删除**（否则双重扣费），只保留把 `AiGovernanceException` 映射成 429。`AiController.chat` 同样 catch 该异常返回 429。
- **为什么单测没拦住**：`BillingServiceTest` 自己 seed 裸 key、自己用裸 key 调 charge，与生产口径脱节，恒绿。已改为 descriptor 常量并加 `charge_裸模型名不命中倍率_口径已统一为descriptor` 固化。
- **真正的锁是 E2E**：`AiBillingE2ETest`（bootstrap）用绑账号、余额 0 的普通 key 打两条入口，断言都 429。已验证摘掉计费点即红（200）、恢复即绿。
- **`ApiKeyRepository` 加 `findByCredentialId`**（治理契约只拿得到 subjectId/credentialId，需反查 scope 与计费账号；`*` scope 与查不到记录的 bootstrap/内部凭据免计费）。改这个接口须同步 `ApiKeyServiceTest.InMemoryApiKeyRepository` 桩。
- **诚实标注**：`local-rule` 不上报 TokenUsage，token 恒 0、扣减恒 0，所以 E2E 只能锁「入口是否受额度约束」，金额口径的回归由单测覆盖；`ai_usage_log` 的 `points_cost`/`account_id` 仍未填充；旧库里运维手工加的其它裸 key 需人工对账。
- **下一轮 4.2 的前置问题**：当前所有 API key 的 `subjectId` 都继承签发者（bootstrap 签发的是 `subject_owner`），没有独立 Subject 签发入口。此时把策略挂 subjectId 会塌缩成「全体一份」——4.2 开工前要先决定补独立 Subject 签发还是改挂账号维度。
**控制台 4.2「AI 权限与预算」完成（2026-08-19）**：策略维度定案 + 分层判定 + 运行态可见。
- **策略挂账号维度**（`account_records.id`，与 `point_account` 同口径），不挂 subjectId。理由：`api_key_records.subject_id` 在 `ApiKeyService.persistNewKey` 里 `setSubjectId(issuer.subjectId())` 原样继承签发者，而控制台唯一签发者是 bootstrap principal（subjectId 是常量 `subject_owner`），挂上去所有 key 共享一条策略。现有计费也早已是账号维度（`BillingAiGovernance` 完全忽略 subjectId）。roadmap 原写的 `PUT /admin/v1/ai/policies/{subjectId}` 已改为 `{accountId}`。
- **判定顺序是安全语义，不能重排**：`AiPolicyService.checkGlobal`（运行期开关 + 全局白名单）**必须在 `resolveBillingAccount` 之前**。管理员凭据（scope `*`）在解析处 `return free()` 短路，顺序反了全局策略对它完全失效 —— 管理员 key 泄漏即无限免费调外部模型。E2E 已锁（摘掉 checkGlobal 立刻红）。
- **两个开关别混**：`twinkle.ai.enabled` 是 Micronaut `AiEnabledCondition` 装配期条件，运行期改不了；`ai.runtime.enabled`（ConfigFacade / 配置中心）才是运行期软开关，关闭后 AI bean 仍装配、只是治理层拒绝。
- **限额语义**沿用 subscription_plan：`>0` 生效、`0` 不限制；**无策略行 = 不限制**（新表上线不锁死存量账号）。日窗口 24h 滚动，计数器走 `AiUsagePolicyMapper` 的 `@Update` 原子自增 SQL（项目里唯一的注解 SQL，因为读-改-写并发会丢计数）。
- **模型白名单的真实语义**：模型是装配期单例 `AiModelBundle`，白名单是"当前模型是否放行"，不是多模型路由选型。descriptor = `provider/modelName`（本地是 `local-rule/deterministic`）。
- **拒绝码按性质分流**（此前两处硬编码 429）：`AiGovernanceException.Kind` = QUOTA→429 可重试 / UNAVAILABLE→503 / POLICY→403 不可重试。映射点两处：`ToolExecutionService.governanceError` 与 `AiController.chat`。
- **`AgentStatusService` 是新 core 契约**（不是扩 `ServerAgentService`）：http-api 只依赖 core+data、不依赖 ai（已核对 pom），运行态必须经 core 契约。拆开也避免动 `UnavailableServerAgentService` 与 `ToolCatalogServiceTest.AvailableAgent` 两个桩。
- **`permissionVersion` 刷新不是生效机制**：治理层每次实时查库，写库即生效；刷新只让客户端 catalog etag 失效 + 审计带新 policyVersion。别当成保障。
- **顺带修的既有缺陷**：`ai_usage_log` 的 `points_cost`/`account_id` 回填（此前恒 0，靠 `settle` 返回值）；该表自 V4 建表起无任何索引，补了 `(account_id, created_at)`；`record()` 显式写 ISO-8601 `created_at`（DB 默认在 SQLite 是空格分隔，与按 ISO 传的 from/to 做字典序比较会错开，存量行仍是旧格式）。
- **诚实标注**：`local-rule` 不上报 TokenUsage，token/扣分恒 0，token 维度日预算只能靠单测锁；管理员用量 `account_id` 落 0（列 NOT NULL DEFAULT 0）；日限额因 precheck/settle 两阶段有窗口，是近似值非硬上限；预算是账号粒度，per-Credential 未做。
- **已知遗留（非本次引入）**：`twinkle-web/src/pages/roles-page.tsx:130` 有 `react-hooks/set-state-in-effect` lint error，上一轮 RBAC 前端提交引入，`npm run lint` 因它失败（已用 git stash 验证与 4.2 改动无关）。

- **顺带暴露的既有测试隔离缺陷（写 E2E 必读）**：`MybatisFlexBootstrap.start()` 注册的是**全局默认 DataSource**，同一 JVM 内先启动的 `ApplicationContext` 会被后续测试类共享——即使各测试用不同的临时 SQLite 路径，**后跑的测试类读到的仍是先跑那个的库**。因此 E2E 里**禁止对表做绝对计数断言**（`count() == 1`），必须记基线算增量。新增 `AiBillingE2ETest`（字母序在 `HttpApiE2ETest` 之前）后，`HttpApiE2ETest` 的 `toolAudits.count()==1` 立刻变成 6；同文件 :310 的 `isGreaterThanOrEqualTo(7)` 说明前人早已踩过但只局部妥协。已把该测试四处绝对计数改为增量断言。根治（每类 fork 新 JVM / DataSource 隔离）未做。
