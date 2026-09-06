# Channel Worker 部署模型

`channel` 角色现在表示一个工作进程，不再等同于单个频道。一个 worker 共享 WZ、脚本引擎、
主 Tick 与存档单写队列，同时为每个频道创建独立的端口、协议注册表、玩家/会话表、地图运行态、
怪物租约与管理视图。

```bash
java -jar target/twinkle-server.jar \
  --twinkle.profile=split-channel \
  --twinkle.role=channel \
  --twinkle.worker.id=worker-a \
  --twinkle.worker.channels=1:8584,8:9000,21:10000
```

频道 ID 是稳定、不透明且允许稀疏的 v83 标识（范围 `1..256`）。频道数量、列表位置、端口和
worker 分组都不能用于推导频道 ID。登录列表会写入每个条目的真实 `channelId - 1`；选角和
换频道始终从 coordinator 查询真实 host/port。worker 断链后对应频道立即从可用目录移除，
重连时仍使用原 ID。

本机 split 脚本使用显式拓扑，不再接受“频道数量 → 连续 ID/端口”的推导：

```bash
TWINKLE_WORKERS='worker-a=1:8584,8:9000;worker-b=21:10000' \
  ./scripts/split-start.sh
```

留空 `twinkle.worker.channels` 时继续读取旧的 `twinkle.net.channel.id` 和
`twinkle.net.channel.port`，因此每 worker 一个频道仍受支持。

构建只产生一份可执行制品：

```bash
mvn -B -pl bootstrap -am package
```

输出为 `twinkle-server/target/twinkle-server.jar`。coordinator 和所有 worker 都运行这同一文件，
仅启动参数不同。Web 上的脚本/WZ 重载由管理进程按 `workerId` 去重后并发调用；一个 worker 内
只扫描一次脚本、只构建并发布一次 WZ 快照，同时刷新它托管的全部频道地图投影。

管理面的游戏网络状态保留旧的单频道字段用于兼容，同时通过 `channels` 返回 Worker 内全部
真实端点及监听状态。旧字段取 Worker 中 ID 最小的频道，不得再用于推断频道总数或连续范围。

当前集群的大区 ID 通过 `TWINKLE_WORLD_ID` / `twinkle.net.world.id` 显式设置，可在 `0..254`
内选择任意稳定值（`255` 是 v83 列表结束标记）。登录列表、查角色、建角和“查看全部角色”
都使用这个真实值，不再硬编码为大区 0。当前运行档仍是单大区；未来扩展多大区时必须继续使用
显式 ID，不允许根据大区数量重排已有 ID。
