# M5：Web 控制台 + 迁移 + 上线切换

> 对应 [ARCHITECTURE.md](../../ARCHITECTURE.md) 第二节（Web 控制台替代 JavaFX）、第四节 4.6.2（admin 并入管理进程）、第六节（数据层迁移）、第十一节 M5。
>
> 状态：**完成（后端面）** ｜ 前置依赖：M4 ｜ 影响模块：`admin`、`http-api`、`data`（迁移）

## 目标

Web 控制台（替代 JavaFX）、单库迁移、上线切换。

**交付**：控制台后端运维 API、单库迁移、上线切换。

> **完成范围诚实标注（2026-08-09）**：Web 控制台**前端页面本轮未做**（用户决策：前端形态尚未设计）。
> 本轮落地的是控制台**后端运维 API**（`/admin/v1/*`，见下）+ 单库迁移 + 上线切换。前端页面（twinkle-web
> 重建或 admin 内置轻量页）留后续里程碑，API 面已就绪不搬家。详见文末"完成范围诚实标注"节。

## 任务清单

### 1. Web 控制台（admin）

- [x] 运维 API 落地（**放 http-api 模块，路由 `/admin/v1`**）：频道状态（`IntercoordService.channels()`）、
      在线玩家（③只读镜像）、配置热改（走配置中心变更链路 `DbConfigFacade.upsert`）、运维操作
      （踢下线/脚本重载/逻辑重载/L4重启，全经 service 接口或 core 契约）
- [x] 控制台能力：频道状态、在线玩家、配置热改（走配置中心变更链路）、运维操作
- [x] 运维操作经 service 接口（不得直踩游戏内存）——`AdminService` 扩展 `reloadScripts/requestRestart/restartPhase`，
      `IntercoordService` 扩展 `channels()`；ScriptManager/RestartService/ChannelRegistry 具体类一律经 core 契约
- [x] admin 并入管理进程（低频、无状态、只经 service 接口，为隔离单独付 JVM 不值）——`AdminConsoleController`
      在 http-api（管理侧），`admin` 模块保持 AdminMarker 占位（前端未设计，托管留后续）
- [ ] **前端页面（未做）**：Web 控制台 UI 形态尚未设计（用户决策），后端 API 已就绪

### 2. 单库迁移

- [x] 老库（参考项目）→ 新结构迁移：migration 管结构（`V7__queststatus_newmaple_columns.sql` 补列）、
      seed 管内容（`NewMapleImporter` 拷贝引擎 + `NewMapleImportMain` CLI）
- [x] 表结构不改，扩展走 migration `ADD COLUMN`（红线 2：禁止单条 ALTER 串接多列）——V7 每列一条独立 ALTER
- [x] `characters` 存档结构兼容迁移（74 列，红线 3：结构不变）——`NewMapleImporter` 显式列清单 + 显式主键
- [x] 数据验证：迁移后老存档可读、行为对齐参考项目——`MigrationImportE2ETest`（导入 → CharacterLoader 加载断言）

### 3. 上线切换

- [x] `--profile` 装配的正式档位确认（默认 `single`，`BootstrapProfile.parse` 未知回落 SINGLE）
- [x] 日志规范（log4j2，`log.error("描述", e)`，禁用 `e.printStackTrace()`，红线 9）——`LoggingDisciplineTest` 静态扫描守护
- [x] 切换脚本 / 步骤——`scripts/start.sh` + `scripts/migrate-newmaple.sh` + `docs/archived/ops/switch-to-production.md`
- [x] 灰度 / 回滚路径（CC 兜底：升级前挪玩家 → 重启 → CC 回来）——`scripts/rollback.sh` + 文档第 3/4 节

## 验收标准

- [x] Web 控制台可用（频道状态 / 在线玩家 / 配置热改 / 运维操作）——`AdminConsoleE2ETest` 全端点验证
- [x] 单库迁移成功（老库数据无损、老存档可读）——`NewMapleImporterTest` + `MigrationImportE2ETest`
- [x] 上线切换完成（默认档跑通，回滚路径验证过）——`mvn -B verify` 全绿 + 文档含回滚脚本

## 风险与注意

- **迁移别破兼容红线**：`newmaple` 表结构与 `characters` 存档结构三条兼容红线（2/3）在迁移期最易破，迁移后必须 parity 验证。
- **控制台运维操作不得直踩游戏内存**：经 service 接口，同 HTTP/AI 约束。
- **主动重开 ≠ 全量落盘**（红线 17）：上线切换靠 CC 兜底 + 增量 FLUSH，不是"等几分钟停服"。

## 完成范围诚实标注（后续开发必读）

- **Web 控制台前端页面：未做**。用户明确"前端形态尚未设计"。后端 `/admin/v1` 运维 API 全落地（频道状态/在线/
  配置热改/踢人/脚本重载/逻辑重载/重启），前端（重建 twinkle-web 或 admin 内置轻量页）留后续里程碑，API 不搬家。
- **`/admin/v1` 无鉴权**：与 `/internal/v1` 同待遇，仅靠 loopback 绑定兜底（红线 20 网络平面收敛，
  `micronaut.server.host` 默认 127.0.0.1）。强鉴权（登录会话 + 角色权限）留后续安全里程碑。
- **老库密码不保真**：`NewMapleImportMain --reset-passwords`（默认启用）把非 BCrypt 老哈希重置为
  `changeMe123!`（twinkle 登录用 BCrypt.checkpw，直接带老哈希登不上）。双轨校验列后续增强。
- **好友列表不迁移**：newmaple `buddies` 与 twinkle `buddylist` 结构/语义不兼容，导入跳过（记录跳过条数）。
  好友关系经游戏内加好友流程重建。
- **queststatus 兼容列**：V7 补 `expires/forfeited/completed/info` 四列对齐 newmaple 表结构（红线 2）。
  twinkle 领域层当前不使用，默认 0 对齐默认语义。`QuestStatusEntity` 已加字段，`QuestRepository.replaceAll` 天然保留。
- **频道启动注册钩子**：新增 `ChannelRegistryRegistrar`（bootstrap @Context 装配），构造期上报频道到
  coordinator 注册表——此前生产无人调 `registerChannel`，`/admin/v1/channels` 恒空。这是控制台频道列表可用的前提。
- **重启测试安全**：`twinkle.admin.restart.exit=false` 供测试/开发（只编排不真退出）；生产默认 `true`（编排完 System.exit，
  由外部启动脚本拉起）。E2E 不触发真实退出，编排正确性由既有 `L4RestartE2ETest` 覆盖。
- 测试全绿：`mvn -B verify` 17 模块 SUCCESS，bootstrap 33 测试全绿（新增 AdminConsoleE2ETest / MigrationImportE2ETest /
  LoggingDisciplineTest / NewMapleImporterTest / V7QueststatusColumnsMigrationTest / ChannelAdminServiceTest 扩展）。


## 后续变更（2026-08-09，覆盖本节上文）

**newmaple 兼容已删除**：用户明确"这是全新自研项目，不兼容 newmaple/北斗"。`NewMapleImporter`/
`NewMapleImportMain`/`MigrationImportE2ETest`/`NewMapleImporterTest`/`V7QueststatusColumnsMigrationTest`/
`migrate-newmaple.sh` 全部删除；`V7__queststatus_newmaple_columns` 并入 `quest_status` 建表（四列一次建全）。
上文勾选的迁移/导入项不再适用——见 `docs/archived/ops/switch-to-production.md` 新库自建说明。
