# 业务逻辑发布与奖励接口

## 发行结构

从仓库根目录构建前端后，使用 GraalVM JDK 21 执行 `mvn -B -f twinkle-server/pom.xml clean verify`。

```text
twinkle-server/target/
├── twinkle-server.jar
└── logic/
    ├── game-logic.jar
    ├── login-logic.jar
    ├── admin-logic.jar
    ├── query-logic.jar
    └── coordinator-logic.jar
```

首次部署必须带上整个目录。主 JAR 不包含五个业务模块的实现类。单进程仍只有一个 JVM；分进程按角色加载自己需要的业务包。默认从主 JAR 旁的 `logic/` 加载，可用 `TWINKLE_LOGIC_PATH` 或 `twinkle.logic.path` 指定。

此次新增的奖励回执字段进入开发期 V2 建表脚本，只在临时 SQLite 验证。已有数据库不能把改写的 V2 当作增量迁移；正式升级需按实际数据库基线另行安排迁移，本次没有操作正式环境。

## 检查与发布

保留当前发行目录，在另一个目录构建候选版本。执行：

```text
python twinkle-server/scripts/logic-release.py <当前发行目录> <候选发行目录>
```

`canHotReload=true` 才能在线发布；`changedModules` 是本次业务模块，`hostChanges` / `incompatibleModules` 指出需要重启的变化。工具比较 JAR 内容，忽略 ZIP 时间；已热更过的目录按 `.cache` 中的活动版本比较，支持回退到之前的兼容代码。

检查通过后加 `--stage`，工具把有变化的业务包写入当前发行目录的 `logic/incoming/`。它不会替换主 JAR、启动服务或自动切换运行版本。不要覆盖启动目录里的主 JAR来实施热更。

控制台“运维操作 → 业务模块 → 重载逻辑”，或调用：

```text
POST /admin/v1/reload/logic?module=game-logic
X-Admin-Token: <管理员会话>
X-Admin-Reason: <发布原因>
```

需要 `admin.reload:logic` 权限。其他模块使用对应名字。分进程部署时，游戏逻辑按 worker 去重下发 RPC；每个 worker 的 incoming 目录均需事先有同一候选制品。其他四个模块在管理进程切换。

响应包含制品 SHA-256 与逐目标结果：`APPLIED` 为已确认切换，`FAILED` 为该目标未完成，`UNKNOWN` 为 RPC 结果未确认。部分失败返回 HTTP 409。再次提交同一候选可补齐失败目标，成功目标不会重复推进版本。RPC 超时不能据此认定远端没有执行。

`GET /admin/v1/reload/logic` 返回本进程各模块和执行属主的摘要、退役代数量与清理状态。旧的 `/internal/v1/reload` 已退役，不再用计数换代冒充逻辑加载。

## 切换与释放

1. 复制候选到按内容摘要命名的缓存，检查模块归属、共享调用契约、宿主服务契约和实现类签名。禁止逻辑包夹带宿主类或直接引用别的业务模块实现。
2. 为所有目标准备完整逻辑对象。准备失败，所有目标保留旧版。
3. 每频道在自己的 FIFO 安全点切换，先取消该频道未完成交易并检查结果；一次游戏操作固定同一代。管理与查询调用在一次完整方法调用内固定同一代，进行中的调用允许结束。
4. 频道安全点等待最多 5 秒；超时取消切换令牌，晚到的任务不能补做一次未报告的切换。其他频道继续处理。
5. 旧调用退出后释放业务实例引用并关闭旧加载器；JVM 在后续 GC 中卸载不再可达的类。旧调用或清理尚未完成时拒绝继续换代；上次部分失败时只允许重试同一候选，避免不断叠加版本。
6. 写入活动摘要启动记录。重启恢复已确认的候选，incoming 中的损坏候选不会污染启动。启动记录写入失败单独报告 `startup-record=FAILED`，应重试同一候选。

缓存中可保留历史 JAR 供审计/回退，磁盘文件存在不代表旧实现仍在运行。回退代码也要走上述检查与切换；不会回滚已发放奖励或其他业务数据。多模块发布逐模块报告，不承诺跨模块或跨频道全局原子切换。

## Actor 式奖励发放

执行归属是**频道队列**，每个玩家的一笔奖励是一条独立消息。不是每人创建线程，也不为每个玩家另造一套长期 Actor 对象。同频道串行，不同频道独立；数据库保存走已有后台单写队列。

```json
{
  "batchId": "activity-20260911-001",
  "characterIds": [1001, 1002, 1003],
  "items": {"2000000": 10},
  "meso": 5000,
  "experience": 1000
}
```

当前支持普通装备和 2～4 类背包物品；现金/宠物模板返回 `INVALID`。经验按数值增加，完整升级结算仍属于既有游戏路线图的后续功能。

提交到 `POST /admin/v1/rewards`，需要 `admin.reward:grant` 和 `X-Admin-Reason`，每次最多 100 人。共享请求参数非法会拒绝整个请求；某个玩家离线、忙碌、背包满或执行异常，只影响该玩家的结果。

| 返回状态 | 含义与重试 |
|---|---|
| `APPLIED` | 物品、金币、经验和幂等回执已一起存档成功 |
| `ALREADY_APPLIED` | 同批次同内容已发放，不再加资产 |
| `OFFLINE` / `BUSY` / `NO_SPACE` | 未发放，条件满足后重试该玩家 |
| `IDEMPOTENCY_CONFLICT` | 同玩家同批次使用了不同内容，拒绝修改 |
| `PERSISTENCE_PENDING` | 存档或 RPC 结果未确认，用原 batchId 和原内容重试 |
| `INVALID` / `FAILED` | 参数或执行失败，检查后按原请求重试 |

幂等键是 `batchId + characterId`，回执与资产处于同一存档事务。满背包等预检失败不改变任何资产；存档失败后只重存快照，不重复加物品、经验或金币。回执随角色持久化，不按内存版本清空；当前不自动过期，保证历史批次重试也不重复发放。失败玩家可单独重试，无需重发整批。

新增逻辑实现不得保存角色、连接、执行队列的私有副本，不得自行注册线程或永久回调。跨调用状态留在宿主；延迟游戏写必须经 `GameExecution.continueAt` 携带版本。
