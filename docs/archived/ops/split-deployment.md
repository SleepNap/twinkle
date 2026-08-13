# twinkle split 分布式部署操作手册（架构 M6）

> 本文档记录 twinkle split 档（分布式）的部署、启动、滚动升级、coordinator 重启与回滚流程。
> 配套脚本：`scripts/split-start.sh`、`scripts/rolling-restart.sh`。
> 单进程档（默认）操作见 `docs/archived/ops/switch-to-production.md`。

## 1. split 档形态与代价

**拓扑**（架构 4.6.2）：管理进程（coordinator + login + admin + http + ai）1 个 + 每频道 1 个进程。

```
┌──────────────────────────────┐
│ 管理进程（coordinator 角色）    │  ← --twinkle.role=coordinator
│  coordinator 真值/定位/路由     │
│  login / http / ai / admin     │
└──────────┬───────────────────┘
           │ TCP 长连接（内部帧：注册/心跳/EVENT/RPC）
     ┌─────┴──────┐       ┌─────┴──────┐
     │ 频道进程 1   │  ...  │ 频道进程 N  │  ← --twinkle.role=channel
     └────────────┘       └────────────┘
```

**物理代价（诚实声明，架构 4.1）**：多 JVM 常驻开销 + 共享数据变"每进程各一份 + coordinator 同步"。
**2C2G 强制单进程（红线 15）**——split 仅限大内存机器 / 多机。

## 2. 配置项

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `twinkle.profile` | `single` | split 档设 `split-channel` |
| `twinkle.role` | — | `coordinator`（管理进程）/ `channel`（频道进程） |
| `twinkle.coordinator.host` | `127.0.0.1` | coordinator 内部通信监听地址（架构 4.6.4） |
| `twinkle.coordinator.port` | `8510` | coordinator 内部通信端口（星形中心） |
| `twinkle.net.channel.id` | `1` | 频道 id（split 每频道进程 1/2/...） |
| `twinkle.net.channel.host` | `127.0.0.1` | 频道注册上报 host |
| `twinkle.net.channel.port` | `8584` | 频道客户端端口（每频道 +1 递增） |

各进程共享同一 DB（`twinkle.db.url`，单机多进程同一库；跨机需共享网络 DB）。

## 3. 启动（scripts/split-start.sh）

```bash
# 构建
mvn -B verify

# 启动 3 频道 split 档（coordinator + channel-1/2/3，端口 8584/8585/8586）
TWINKLE_CHANNEL_COUNT=3 ./scripts/split-start.sh
```

启动自检：
```bash
curl http://127.0.0.1:8080/admin/v1/health       # healthy
curl http://127.0.0.1:8080/admin/v1/channels     # 3 个频道（注册表重建）
curl http://127.0.0.1:8080/admin/v1/online       # 在线玩家（只读镜像）
```

## 4. 滚动升级（scripts/rolling-restart.sh，架构 M6 第 7 节）

频道进程是故障隔离单元——一个频道崩了不连累全服。升级 = 逐频道：玩家 CC 迁出 → 重启该频道（换新 jar）→ 玩家 CC 回来。

```bash
# 滚动升级全部频道（逐个）
./scripts/rolling-restart.sh

# 只升级频道 2
./scripts/rolling-restart.sh 2
```

脚本流程：`POST /admin/v1/restart`（经 AdminService RPC 到频道：DRAINING → 增量 FLUSH → 退出）→ 等待该频道从注册表消失 → 拉起新频道进程 → 等待重新注册。

**玩家视角**：升级期间"闪一下"回来（换频道 loading 界面，架构 4.7 兜底性）。

## 5. coordinator 无状态重启（架构 4.2）

coordinator 是**无状态**的：定位表/注册表靠频道上报 + 心跳重建，不需要主备选举。协调者进程崩了 = 无状态进程重启。

```bash
# 杀掉 coordinator 进程
kill <coordinator_pid>

# 重启（同命令）
java -jar target/twinkle-server.jar --twinkle.profile=split-channel --twinkle.role=coordinator \
  --twinkle.coordinator.port=8510 > logs/coordinator.log 2>&1 &
```

**自动重建**：频道检测断链 → 定时重连 coordinator（InternalClient）→ 重连成功重报 REGISTER → 定位表/注册表自动重建（验证见 `SplitChannelE2ETest#coordinatorRestartRebuildsRegistry`）。

**coordinator 宕机期间**（架构 4.5 故障矩阵）：跨频道功能停摆，单频道本地逻辑继续（不依赖 coordinator）——降级为单频道可玩。

## 6. 回滚

split 档回滚 = 逐频道回滚（同滚动升级，只是换旧 jar）：
```bash
# 还原旧 jar
cp ./backup/twinkle-server.jar.bak target/twinkle-server.jar
# 逐个频道滚动重启换回旧版本
./scripts/rolling-restart.sh
```

## 7. 已知差异与限制（诚实标注）

- **CC 迁移**：跨进程链路已通（可靠总线 + 目标频道恰好一次消费 + 同步存档），但客户端真实重连的 loading 界面需真机验证（同 M1/M2 客户端验收）。
- **AdminService 运维操作**（kick/reload/restart）：管理进程经 `RemoteAdminService` RPC 到频道进程；频道未连接时降级（在线快照空、kick false）。
- **喇叭道具 / 加好友完整玩法**：M4 标注未做（活动公告广播已通，跨频道走消息总线）。
- **真实多机**：单机多进程走 loopback（架构 4.5"分布式特例"）；跨机仅需把 `twinkle.coordinator.host`/`twinkle.net.channel.host` 设为真实地址，机制完全复用。
- **single 档性能不退化**：默认 single 档不启内部通信，语义与 M5 完全一致（全量测试兜底）。
