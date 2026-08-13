# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目状态

**热更新、扩展性好的冒险岛后台（MapleStory v83 服务端）**。参考项目：北斗（`E:\LocalGit\GitHub\BeiDou-Server`，GPL，只作理解、禁止逐字复制）。

**M0-M6 全部完成**（2026-08-09）：17 个 Maven 模块、全量测试通过。当前焦点转向**能力面**：twinkle 定位为服务端能力提供方，经 `/api/v1` 向外部 agent 客户端 **twish** 提供 Tool-first 能力面（api-key + scope + 审计）；`ai/` 模块改为可选装配（`twinkle.ai.enabled`，默认关，同 WAL 按需启用）。契约与形态决策见 `docs/archived/twish-capability-api.md`、`docs/archived/ai-agent-architecture-decision.md`。

**Web 控制台已进入正式业务开发**（2026-08-12）：`twinkle-web/` 已落地 shadcn `radix-nova` 控制台框架、路由、管理 API 层，以及运行概览、频道、在线玩家、账号角色、配置中心、运维操作、API Key、审计日志和任务监控页面；配置热改、踢下线、脚本/逻辑重载、重启、API Key 生命周期与 Scope 调整均已接入确认和反馈。统一 `BackgroundTaskRegistry` 已提供有界执行历史、调度启停、立即运行与失败重试，`AiDailySummaryScheduler` 为首个真实接入任务。完整范围见 `docs/in-progress/console-roadmap.md`，后续继续补齐强鉴权、AI 预算/模型策略、任务持久化与集群聚合和部署。

**全局 i18n 全量完成**（2026-08-12 基座，2026-08-13 迁移完成）：`twinkle.service.language` 是 Java 后台唯一语言配置，HTTP 用 `Content-Language` 声明实际语言；Web 控制台首批支持 `zh-CN`/`en-US` 并独立持久化界面选择。存量 Java 日志/异常/游戏内玩家提示中文硬编码已全部迁入 i18n key（约 300 处，跨 12 模块），`en-US` 配置下后台文案全英文。统一入口：`I18nService`（`@Singleton`，可注入）+ 静态门面 `org.gms.i18n.I18n`（无法 DI 的代码用，bootstrap `I18nInitializer` 启动注入）。key 前缀：`log.*`（日志，`{}` 占位）/ `error.*`（异常，`{0}`）/ `game.*`（游戏内提示，`{0}`）。编码规则：协议层固定 GBK（`InPacket.DEFAULT_CHARSET`，GBK 兼容 ASCII），语言只改文案内容不改编码，HTTP 固定 UTF-8。WZ/脚本不受服务端语言控制，只读 `twinkle.wz.path` / `twinkle.script.path` 显式目录。规范见 `docs/archived/i18n.md`。

**`ARCHITECTURE.md` 是唯一权威规范**——设计决策、模块划分、运行拓扑均以该文档为准，改动前必须先读。所有文档、注释使用中文。

各里程碑进度、遗留与"完成范围诚实标注"见共享记忆 `.claude/memory/twinkle-project-context.md` 与 `docs/archived/tasks/` 任务文档（根 CLAUDE.md 不复述逐项明细）。

## 常用命令

在 `twinkle-server/` 下执行（构建基线：**JDK 用 GraalVM for JDK 21**，版本须与 pom 的 `graalvm-js.version` 匹配，见 README「环境要求」）。

- **全量构建 + 测试**：`mvn -B verify`（17 模块；含 JaCoCo 覆盖率报告 + ArchUnit 架构测试 + LoggingDiscipline 静态扫描）
- **单模块单测**：`mvn -pl <模块> -am -Dtest=<测试类> -Dsurefire.failIfNoSpecifiedTests=false test`（`-am` 带上游依赖；`-Dsurefire.failIfNoSpecifiedTests=false` 防上游模块因无匹配测试报错——实测必需）。例：`mvn -pl bootstrap -am -Dtest=BootstrapContextTest -Dsurefire.failIfNoSpecifiedTests=false test`
- **只编译不测**：`mvn -B -DskipTests compile`
- **启动（single 档）**：`./scripts/start.sh`（前置：`mvn -B verify` 产物作 `target/twinkle-server.jar`；默认 `--profile=single`）
- **split 多进程 / 滚动重启 / 回滚**：`./scripts/split-start.sh` / `./scripts/rolling-restart.sh [频道号]` / `./scripts/rollback.sh`（见 `docs/archived/ops/split-deployment.md`、`docs/archived/ops/switch-to-production.md`）
- **启动自检**：`curl http://127.0.0.1:8080/admin/v1/health`、`/admin/v1/channels`、`/internal/v1/health`

关键配置（默认值，env 可覆盖）：`twinkle.profile`=`single`（`standalone` 低配 / `split-channel` / `split-realm`）、`twinkle.db.url`=`jdbc:sqlite:./data/twinkle.db`、`twinkle.service.language`=`zh-CN`、`twinkle.net.login.port`=`8484`、`twinkle.net.channel.port`=`8584`、`micronaut.server.host`=`127.0.0.1`（默认绑 loopback，红线 20）、`micronaut.server.port`=`8080`。`data/twinkle.db` 为运行期产物不入仓。

## 能力面与外部 agent（twish）

twinkle 服务端 = **能力面（主）+ 可选 agent 宿主（辅）**。对外能力统一经 `/api/v1` 暴露，鉴权走 **API-key（Bearer）+ scope 授权 + 审计**（`api_request_audit`/`tool_execution_audit` 落库）；客户端不可信，危险操作一律服务端执行后回传结果，不直踩游戏内存。

- **twish**：外部 agent 客户端，独立仓库（契约真值在 twish 仓库 `docs/server-tasks/`，twinkle 侧接入说明见 `docs/archived/twish-capability-api.md`）。只读闭环：`identity/me → capabilities → tool-executions → auditRef`，首批 Tool 为 `server.health.read@1.0.0` 与 `player.online.list@1.0.0`。
- **服务端 agent 宿主**（`ai/` 模块）：保留但不默认装配，`twinkle.ai.enabled=true` 显式启用（2C2G 红线）。覆盖"玩家触发游戏事件 / 日志突变"等外部 agent 覆盖不了的服务端主动场景。`LocalRuleChatModel` 为自研规则路由，换真实 LLM 只换 `ChatModel` bean（`TWINKLE_LLM_API_KEY` env）。
- **生产身份配置**（必配）：`TWINKLE_SERVER_ID` / `TWINKLE_SERVER_NAME` / `TWINKLE_SERVER_ENVIRONMENT` / `TWINKLE_CURSOR_SIGNING_KEY`（≥32 字节 HMAC）；首次签发用 loopback-only 的 `TWINKLE_API_BOOTSTRAP_KEY`。
- 管理侧/能力面不得依赖 `domain-game`（红线 3）；数据三路：查 DB（data repository）/ 经 `AdminService` core 契约 / 事件快照只读镜像（`OnlinePlayerMirror`）。

## 架构（三条铁律 + 关键约束）

完整设计见 `ARCHITECTURE.md`。以下是从中提炼、写代码时必须遵守的硬约束：

### 三条铁律

1. **一套代码、多种拓扑**：单进程（`--profile=single` 性能档 / `standalone` 低配档，默认 `single`）↔ 单机多进程 ↔ 跨机分布式，配置驱动切换。任何涉及"另一个东西"的接口从第一天起**不假设它在进程内**。判断标准：`单进程下 X 是什么形态？分布式下 X 变成什么？`
2. **性能是硬指标**：2C2G 单进程必须能跑（硬红线）。任何默认引入重服务、常驻大内存、独立进程的方案都违反此线。
3. **状态与逻辑分离**：游戏实体 = 纯数据 + 操作数据的逻辑系统。这是热重载、分布式、插件三者的共同地基。

### 模块结构（Maven 多模块，依赖单向无环）

- **公共底座**：`bootstrap`（唯一 main，读 `--profile`）、`core`（DI/EventBus/配置/调度/插件/热更新）、`net-netty`、`net-packet`（v83 opcode/HandlerRegistry）、`data`（MyBatis-Flex + 自研迁移器）、`db-dialect`、`plugin-api`
- **游戏域**（频道进程）：`domain-game`、`domain-script`（GraalVM JS）、`wz-provider`、`channel`
- **管理侧**（管理进程）：`coordinator`、`login`、`admin`、`http-api`、`ai`

**管理侧不得依赖 `domain-game`**——分进程时两进程不共享内存，single 档同进程也禁止跨依赖（从编译期杜绝 HTTP/AI 直踩游戏内存）。一个 JVM 进程 = 按 `--profile` 装配一组模块。

**进程边界是配置，不是硬编码**：`bootstrap` 是唯一入口；角色（coordinator/channel/login/admin）可内嵌可独立成进程。`2C2G 强制单进程`（红线 15）。

### 分层热更新与安全

- 游戏 tick 单线程；可重载逻辑隔离在**模块 classloader**，状态不进该 classloader。
- **重载原子单元 = 单个玩家/频道**，绝不做全服同步原子重载（交易等跨 tick 操作会丢锁产生复制 bug）。
- L1 配置 / L2 脚本 / L3 逻辑热替换 / L4 进程兜底（DRAINING + 增量 FLUSH）。
- 主动重开 ≠ 全量落盘：只 FLUSH 脏数据，目标是秒级重开 + 不丢档。

### 数据与状态模型

- **内存态是权威，DB 只是持久化 + 查询层**。游戏热路径（tick/战斗/交易）全在内存。
- DB 按需三档：SQLite（低配默认，WAL + 单写连接 + busy_timeout）/ PostgreSQL（大服）/ MySQL/MariaDB（兼容切换）。
- ORM 用 MyBatis-Flex；方言差异点（upsert/自增/布尔/时间函数）集中进 `db-dialect`，业务代码禁止出现裸方言差异。

### 数据库命名与迁移规范（硬约束，写迁移/建表必须遵守）

1. **表名 ≥ 2 个单词**（如 `player_session`、`quest_status`，禁止单单词表名如 `accounts`）；字段名 ≥ 1 个单词。
2. **多单词一律用 `_`（下划线）连接**，禁止无隔离拼接（`nxcredit`❌）也禁止驼峰（`nxCredit`❌）。统一 `snake_case`。
3. **禁止 SQL 关键字作表名/字段名**（如 `int`、`order`、`group` 等）——如需保留语义，改名（`int` → `int_stat`）。
4. **迁移文件目录结构**：`db/migrate/` 下建 `common/`、`sqlite/`、`postgresql/`、`mysql/` 四个目录。三方言**完全一致的语句放 `common/`**；方言有差异的语句分别放到对应目录。
5. **禁止 `-- dialect:xxx` 注释节**（旧机制废除）。方言差异靠"放哪个目录"表达，不靠 SQL 内注释。
6. **迁移命名**：`V<数字>__<snake_case描述>.sql`，每迁移一个主题（建表/补列/seed 分开）。
7. **seed 数据**（如 `param_conf` 初始值）与结构 DDL 分迁移放，便于区分"结构"与"内容"。

> 背景：此规范是用户明确要求（2026-08-09），此前迁移违反（`accounts` 单表名、`nxCredit` 驼峰、`"int"` 关键字字段、`-- dialect:` 节）。重写 V1-V7 时按本规范执行；旧库直接删除重建。

### 技术选型

Micronaut 4（DI/HTTP）、GraalVM CE for JDK 21（原生内置 JVMCI + GraalVM JS，全速无需 EnableJVMCI）、Netty 4（v83 协议字节级不动）、自研迁移器（Flyway 不引入）、log4j2、Bucket4j（限流）、LangChain4j（AI）、WZ/脚本数据源定位（配置直接指定路径、单份数据、读不到报错，见 ARCHITECTURE 6.4）、Web 控制台（JavaFX 明确移除）。

## 硬性编码约束（红线摘录）

1. **v83 协议字节级兼容**：`net.encryption` + `RecvOpcode` 原样移植。
2. **表结构由自研迁移器管理**：迁移 SQL 建表/改表是唯一权威；禁止裸 `ALTER` 串接多列（迁移每列独立语句）。数据库命名与迁移规范见"数据与状态模型"节。
3. **游戏对象不进容器**：Character/MapleMap/Item 手动 new，容器只管基础设施 + Service。
4. **HTTP 与游戏 Netty 隔离 EventLoop**：第三方 API 流量不挤占游戏 tick 线程。
5. `accounts.banned` 只有值 `1` 明确表示已封禁，查询未封禁必须用 `banned <> 1`（兼容 NULL）。
6. 日志统一用 Lombok `@Log4j2` 注解（`log.error("描述", e)`），**禁止手写 `LogManager.getLogger` / `Logger` 字段**；调用统一 `log.xxx(...)`（不用 `LOG.xxx`），禁用 `e.printStackTrace()`。
7. 全限定类名必须 import 后用短名（除非类名冲突）。
8. **可替换层不得引用稳定层具体类**（用接口或数据投影，防 classloader 换代后 CCE）；**不得持有跨操作状态**。
9. **贡献点（PacketHandler/HTTP/Script/Task/Event）从第一天版本化**——可装卸即兼容面。
10. **JDK 锁定 GraalVM CE for JDK 21**。
11. **实体类选型**：一次性初始化、不可变的值对象用 `record`；需要运行时更新的可变实体类用 Lombok（`@Getter`/`@Setter`）代替手写 getter/setter。自定义 `equals`/`hashCode`/`copy` 等仍手写（不被 Lombok 生成覆盖）。
12. **可见性显式声明（必须有头）**：所有类型（顶层/嵌套 `class`/`interface`/`enum`/`record`）与成员（方法/字段/构造器）**必须显式写可见性修饰符**（`private`/`protected`/`public`），**禁止无修饰符的裸声明**。Java 的包私有（default）就是省略修饰符，本项目禁用这种写法：需要"仅同包访问"语义时，把该成员/嵌套类型显式写 `public`，或把只在单类内使用的实现细节收紧为 `private`、把需要子类访问的写 `protected`（语义该是哪个就写哪个）。**禁止 public 成员暴露包私有/私有类型的返回值/参数**（如 `public` 方法返回包私有 `record`）——触发 IDE 警告且泄漏内部类型，应把该类型提升为 `public`，不得靠删成员回避。

## 里程碑

按 `ARCHITECTURE.md` 第十一节推进：M0（骨架+基础验证+热更新地基）→ M1（协议+Netty）→ M2（游戏逻辑重写，参考项目作 parity 真值）→ M3（HTTP+AI+渐进重载）→ M4（插件+热更新 L1-L4）→ M5（Web 控制台+迁移）→ M6（分布式）。**M0-M6 全部完成**（M6 三阶段 2026-08-09：内部通信网络总线 + split 拆进程 + 可靠总线恰好一次 + CC 迁移跨进程 + 升级滚动；2026-08-12 已完成 twinkle-web 的 React 19 + Vite + Tailwind CSS v4 + shadcn 正式初始化和首批真实业务页面，视觉基准固定为 shadcn `radix-nova` 官方风格，不再采用 Notion 仿制主题，见 `docs/archived/design/design-system.md`、`docs/in-progress/console-roadmap.md` 与 `twinkle-web/src/index.css`；各里程碑遗留项见任务文档"完成范围诚实标注"节）。**服务端 Agent 首个闭环已完成**（2026-08-11）：真实 OpenAI-compatible/DeepSeek 模型、只读 GM 工具审计、游戏内 `@gm` 玩家入口已落地，详见 `docs/archived/server-agent.md`。**事故报告阶段 B 已完成**（2026-08-09，`docs/archived/ghost-player-monster-controller-incident.md`）：分阶段心跳 + 会话代际 + 怪物控制租约三块稳定层落地，见项目记忆"幽灵玩家事故报告阶段 B 完成"节。
