# twinkle 上线切换操作手册（架构 M5-3）

> 本文档记录 twinkle 从开发到生产上线的完整切换流程：profile 装配确认、单库迁移、切换步骤、灰度/回滚路径（CC 兜底）、已知差异。
>
> 配套脚本：`scripts/migrate-newmaple.sh`、`scripts/start.sh`、`scripts/rollback.sh`。

## 1. `--profile` 装配档位确认

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `twinkle.profile` | `single`（`TWINKLE_PROFILE` env 覆盖） | 2C2G 强制单进程（红线 15）；未知值回落 SINGLE |
| `twinkle.db.url` | `jdbc:sqlite:./data/twinkle.db` | 低配默认 SQLite；大服 PG/MySQL 覆盖 |
| `twinkle.net.login.port` | `8484` | v83 客户端登录端口 |
| `twinkle.net.channel.port` | `8584` | 频道端口（单进程与登录错开） |
| `twinkle.net.channel.host` | `127.0.0.1` | 频道注册上报 host（架构 4.6.4 注册中心） |
| `twinkle.wz.path` | `./wz` | WZ 数据路径（读不到启动即失败，架构 6.4） |
| `twinkle.script.path` | `./scripts` | 脚本路径（同上） |
| `micronaut.server.host` | `127.0.0.1`（`TWINKLE_HTTP_HOST` 覆盖） | **管理 HTTP 默认绑 loopback（红线 20 网络平面收敛）** |
| `micronaut.server.port` | `8080` | 管理 HTTP / admin 控制台 / 运维 API |

**启动自检**（`./scripts/start.sh` 后）：
```bash
curl http://127.0.0.1:8080/admin/v1/health       # healthy
curl http://127.0.0.1:8080/admin/v1/channels     # 至少 1 频道（注册钩子生效）
curl http://127.0.0.1:8080/internal/v1/health    # 官网/内网健康
```

## 2. 单库迁移（老库 → 新库）

**原则**：migration 管结构、seed 管内容。目标库先由自研迁移器跑 V1-V7 建结构，再由导入工具拷贝内容。

```bash
# 构建
mvn -B verify

# 导入（源 = newmaple MySQL，目标 = twinkle SQLite）
./scripts/migrate-newmaple.sh \
  --source-url=jdbc:mysql://host:3306/newmaple --source-user=root --source-pass=xxx \
  --target-url=jdbc:sqlite:./data/twinkle.db
```

**导入内容**：accounts、characters（74 列，红线 3 兼容）、queststatus（V7 补列后 9 列对齐）、questprogress、inventoryitems（NULL 语义归一）。

**导入跳过**：
- `buddies`（newmaple）→ twinkle `buddylist` 结构/语义不兼容，跳过并计数（好友需游戏中重建）。
- 老库非 BCrypt 密码 → 默认重置为 `changeMe123!`（`--no-reset-passwords` 可关闭，但注意老账号登不上）。

**校验**：导入工具打印各表行数；人工抽查 `SELECT COUNT(*) FROM accounts/characters/...`；启动后用 `CharacterLoader` 加载一个老角色进游戏验证可读。

## 3. 切换步骤（CC 兜底 + 增量 FLUSH，红线 17）

**灰度**：先在新装实例（同一 DB 复制）跑迁移 + 导入 + `mvn -B verify` + 启动自检全通过；生产在低峰窗口执行。

**正式切换**：
1. **备份**：SQLite 直接复制 `data/twinkle.db`；PG/MySQL 用 `pg_dump`/`mysqldump`；另备份旧 jar 与旧配置（`rollback.sh` 读取 `./backup/`）。
2. **触发重启**：`curl -X POST http://127.0.0.1:8080/admin/v1/restart` → 202 accepted。
   - 编排：DRAINING（tick 帧边界停 + 在途交易中断 + 存档队列排空）→ FLUSH_DIRTY（只刷脏，红线 17）→ 退出。
   - **CC 兜底语义**：玩家被踢/断链后客户端回服务器列表，新进程起来后重连（"闪一下"回来，由新进程从 DB 加载恢复）。
   - 可选低配：先 `POST /admin/v1/kick` 把关键玩家引导下线，避免强行断链。
3. **换 jar → 启动**：`cp 新jar target/twinkle-server.jar && ./scripts/start.sh`。
4. **验证**：`/admin/v1/health` + `/internal/v1/health` healthy；`/admin/v1/channels` 频道在；抽查在线/存档。

## 4. 回滚路径（CC 兜底）

健康检查失败 / 行为异常 → 快速回滚，不等停服：

```bash
./scripts/rollback.sh   # 还原 jar + DB 备份 + 配置
./scripts/start.sh      # 起旧版本
# 验证 /admin/v1/health
```

**原则**：切换前先备份（jar + DB + 配置），失败快速还原；靠 `RestartCoordinator`/`CharacterLoader` 从 DB 恢复，不依赖"等几分钟全量落盘"。

## 5. 运维 API 清单（Web 控制台后端，架构 M5-1）

| 端点 | 方法 | 用途 |
|---|---|---|
| `/admin/v1/health` | GET | 健康检查 |
| `/admin/v1/channels` | GET | 频道状态列表 |
| `/admin/v1/online` | GET | 在线玩家 |
| `/admin/v1/reload/in-flight` | GET | 在途实体（重载可观测） |
| `/admin/v1/config` | GET/POST | 配置列表 / 热改（配置中心链路） |
| `/admin/v1/config/{key}` | GET | 单配置项 |
| `/admin/v1/kick` | POST | 踢下线 |
| `/admin/v1/reload/logic` | POST | 逻辑重载（安全点渐进） |
| `/admin/v1/reload/scripts` | POST | 脚本重载（L2） |
| `/admin/v1/restart` | POST | 请求主动重启（L4） |
| `/admin/v1/restart/phase` | GET | 重启阶段轮询 |

> 网络平面收敛：以上与管理 HTTP 一起默认绑 `127.0.0.1`；如需内网/公网暴露，显式配 `TWINKLE_HTTP_HOST`。强鉴权（登录会话 + 角色权限）为后续安全里程碑，当前靠 loopback 绑定兜底。

## 6. 已知差异（诚实标注）

- **密码不保真**：老库非 BCrypt 密码导入后重置为默认口令 `changeMe123!`（清单在导入日志）。双轨校验（支持旧格式验密并升级）列为后续增强。
- **好友列表不迁移**：newmaple `buddies` 与 twinkle `buddylist` 结构不兼容，跳过；好友关系经游戏内加好友流程重建。
- **queststatus 兼容列**：V7 补的 `expires/forfeited/completed/info` 四列对齐 newmaple 表结构（红线 2）；twinkle 领域层当前不使用，默认 0 对齐语义。
- **Web 控制台前端**：本轮落地后端运维 API（/admin/v1）；前端页面形态尚未设计，后续里程碑配套。

## 7. 日志规范检查（红线 9）

- 全仓 `src/main/java` 禁 `printStackTrace()` / `System.out` / `System.err`（`LoggingDisciplineTest` 静态扫描守护）。
- 日志用 log4j2 `log.error("描述", e)`；结构化字段（ts/level/traceId/channelId/playerId）见架构 12.3。
