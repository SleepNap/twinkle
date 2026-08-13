# M6：分布式（大区/频道独立进程）

> 对应 [ARCHITECTURE.md](../../ARCHITECTURE.md) 第四节 4.2-4.6（角色/进程/拓扑/内部通信/分进程组件全图）、4.7（CC 迁移）、第十一节 M6。
>
> 状态：进行中（阶段 A/B/C 完成，验收标准 5 项全勾；剩余项见"完成范围诚实标注"）｜ 前置依赖：M5 ｜ 影响模块：`bootstrap`、`coordinator`、`net-netty`（内部通信）、`channel`、`http-api`

## 目标

分布式：大区/频道独立进程、world-coordinator、CC 迁移、消息总线。

**交付**：多机部署、玩家换频道、升级滚动。

## 任务清单

### 1. 进程边界落地（最高原则 4.1）

- [x] `--profile=split-channel` 拆进程跑通（split-realm 同构未单独验证，留大区形态后续）
- [x] 同一套代码：`single` ↔ `split-*` 配置切换，进程内实现 ↔ 网络实现
- [x] 通信接口与进程内是同一套（接口不假设进程内外，M0 起已如此设计）
- [x] 2C2G 强制单进程（红线 15）：split 仅限大内存机器 / 多机（装配按 role 互斥，single 默认）

### 2. 内部通信机制（4.5，零新依赖）

- [x] 复用 net-netty：每个进程 Netty 客户端 + 服务端，与 coordinator TCP 长连接（InternalServer/InternalClient，A2）
- [x] 自定义二进制帧 `[帧头 | 消息类型 | 消息ID | 负载]`（DefaultInternalFrame + Encoder/Decoder）
- [x] 不用 HTTP（内部进程通信用 Netty TCP；HTTP 专用于对外 API）
- [x] 进程内 EventBus ↔ Netty 网络帧（同一接口，配置切换；RemoteEventBus 实现 EventBus）

### 3. coordinator（共享状态代理 + 定位表 + 路由）

- [x] coordinator 无状态（定位表靠 channel 上报 + 心跳重建，不需要主备选举）
- [x] 内建注册中心：channel → coordinator 单向上报（频道ID、host:port、在线状态）+ 心跳（REGISTER/HEARTBEAT 帧）
- [x] coordinator 崩了无状态重启 → channel 重连重新上报 → 定位表自动重建（SplitChannelE2ETest#coordinatorRestartRebuildsRegistry）
- [ ] 内建配置中心（跨进程形态，4.6.5）：DB 真值 + 版本号 + TCP 长连接推送（M4 进程内 EventBus 订阅已验证；TCP 长连接推送留 M6 后续，诚实标注）
- [x] 消息路由：悄悄话 = 频道A → coordinator（查定位表）→ 转发频道B；广播（喇叭）= 群发所有频道（CoordinatorFrameRouter）
- [x] 星形拓扑，coordinator 单点

### 4. 共享状态单一属主（跨进程）

- [x] 共享状态真值在 coordinator 进程内（IntercoordService RPC 代理；DB 为持久化+查询层）
- [x] 频道需改则发消息、改完来读（单一属主铁律跨进程保持）
- [x] 好友/商店资金走 IntercoordService RPC（接口不变）；公会/排行完整玩法不在 M6（同 M4 标注）

### 5. 第三方 HTTP 跨进程取数（4.6.6，把约束物理化）

- [x] ① 查 DB（主路）：市场记录、商店快照、角色存档
- [x] ② 经 service 接口 → RPC 到频道进程（RemoteAdminService + AdminRpcDispatcher）
- [x] ③ 事件驱动快照：频道进程推变更 → 管理进程只读镜像 → HTTP 读镜像（OnlinePlayerEvents 走 RemoteEventBus）
- [x] 镜像单向、只读，禁止经镜像回写

### 6. CC 迁移跨进程

- [x] 老频道 flush 状态（flushCharacterSync 同步存档）→ 目标频道加载 → 客户端重连（真机 loading 界面留客户端验收）
- [x] 可靠性三件套跨进程：JsonPayloadCodec + bus_stream 持久化去重 + ack 闭环 = 语义恰好一次
- [x] CC 迁移不掉数据、不重复（ReliableReceiver 恰好一次 + 同步存档）
- [x] 兜底用法：升级滚动（玩家换频道 → 重启 → CC 回来，rolling-restart.sh）

### 7. 升级滚动

- [x] 频道逐个滚动升级（一个频道崩了不连累全服，故障隔离；rolling-restart.sh）
- [x] 升级过程玩家"闪一下"回来（真机 loading 界面留客户端验收）

## 验收标准

- [x] 多机部署（单机多进程 split 跑通，single 档性能不退化；真实多机留运维）
- [x] 玩家换频道（CC 迁移跨进程不掉数据、不重复）
- [x] 升级滚动（频道逐个重启，服务不中断）
- [x] coordinator 无状态重启后定位表自动重建
- [x] 悄悄话 / 公告跨频道走消息总线（加好友跨频道投递接口不变，生产接收端同 M4 标注）

## 风险与注意

- **诚实面对物理代价**（4.1）：拆进程不是免费——多 JVM 常驻开销 + 共享数据变"每进程各一份 + coordinator 同步"。**2C2G 强制单进程**，split 仅限大内存机器 / 多机。
- **隔离是买故障隔离，不是买性能**：单机 20 频道若无隔离诉求，`--profile=single` 单进程抗满。
- **可靠性三件套不能省**：本地回环更快但不更可靠，"进程崩了"照样发生。
- **镜像禁止回写**（单一属主铁律）：写操作一律经 service 接口 RPC 到频道进程。
- **HTTP 分进程后"物理隔离"**：两进程不共享内存，HTTP 想碰频道数据都碰不到，只能走三路。

## 完成范围诚实标注（2026-08-09，后面开发必读）

M6 三阶段（A 网络总线/split 拆进程、B 可靠总线恰好一次 + CC 跨进程、C 升级滚动）落地，验收标准 5 项全勾，但部分子项是骨架/简化形态：

| 项 | 完成形态 | 留待 |
|---|---|---|
| **split-realm** | `split-channel` 拆进程全验证；`split-realm` 枚举已支持，未单独跑（同构，role 装配相同） | 大区形态实际部署 |
| **配置中心跨进程**（任务 3 第 4 条） | M4 进程内 EventBus 订阅已验证（ConfigCenterE2ETest）；**TCP 长连接推送未做**（CoordinatorFrameRouter 只路由 EVENT/RPC/REGISTER/HEARTBEAT，无配置版本广播） | M6 后续：配置版本号变更帧 → 各进程重读 |
| **CC 客户端真机** | 跨进程链路全通（同步存档 + 可靠总线 + 目标频道恰好一次消费 + 定位更新）；E2E 验证到"定位表更新" | 真机客户端换频道 loading 界面（同 M1/M2 客户端验收） |
| **AdminService RPC** | RemoteAdminService/AdminRpcDispatcher 实现 onlineSummary/kick/reloadScripts/requestRestart/restartPhase 全方法 | 频道未连接时降级（返回空/0/false），未做管理侧告警 |
| **喇叭/加好友** | 跨频道投递接口不变（NoticeMessage/BuddyRequest 走 RemoteEventBus）；活动公告广播验证过 | 喇叭道具 handler、加好友生产接收端同 M4 标注（M6 未扩展） |
| **真实多机** | 单机多进程走 loopback（架构 4.5"分布式特例"）；跨机仅需改 host 配置 | 真实多机部署 + 网络分区演练 |
| **可靠总线跨机 ack** | 单机多进程共享 DB：接收侧 ack 直接写同一 outbox/bus_stream 表 | 跨机（不共享 DB）需网络 ack 帧返回发送方（ReliableDelivery 序号已随帧携带，ack 反方向未做） |
| **心跳超时标记下线** | HEARTBEAT 帧续期实现；coordinator 心跳超时自动标记下线**未做**（依赖 InternalClient 断链检测触发重连重报） | coordinator 侧定时扫描超时连接 |

**修复的真实 bug**：`InternalClient` 重连失败后 `reconnectScheduled` 不重置，只重试一次就停——修复为 `doConnect` 开始时重置（coordinator 无状态重启重建测试暴露）。
