# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目状态

**热更新、扩展性好的冒险岛后台（MapleStory v83 服务端）**。参考项目：北斗（`E:\LocalGit\GitHub\BeiDou-Server`）。

当前为**仓库骨架**：尚无 pom.xml / 构建脚本 / 业务代码。`twinkle-server`（Java，包根 `org.gms`）与 `twinkle-web`（前端占位）均为空目录。**任何构建命令尚不存在，不要编造**；落地时按 `ARCHITECTURE.md` 从 M0 里程碑开始。

**`ARCHITECTURE.md` 是唯一权威规范**——设计决策、模块划分、运行拓扑均以该文档为准，改动前必须先读。所有文档、注释使用中文。

## 架构（三条铁律 + 关键约束）

完整设计见 `ARCHITECTURE.md`。以下是从中提炼、写代码时必须遵守的硬约束：

### 三条铁律

1. **一套代码、多种拓扑**：单进程（`--profile=single`，默认）↔ 单机多进程 ↔ 跨机分布式，配置驱动切换。任何涉及"另一个东西"的接口从第一天起**不假设它在进程内**。判断标准：`standalone 下 X 是什么形态？分布式下 X 变成什么？`
2. **性能是硬指标**：2C2G 单进程必须能跑（硬红线）。任何默认引入重服务、常驻大内存、独立进程的方案都违反此线。
3. **状态与逻辑分离**：游戏实体 = 纯数据 + 操作数据的逻辑系统。这是热重载、分布式、插件三者的共同地基。

### 模块结构（规划的 Maven 多模块，依赖单向无环）

- **公共底座**：`bootstrap`（唯一 main，读 `--profile`）、`core`（DI/EventBus/配置/调度/插件/热更新）、`net-netty`、`net-packet`（v83 opcode/HandlerRegistry）、`data`（MyBatis-Flex/Flyway）、`db-dialect`、`plugin-api`
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

### 技术选型

Micronaut 4（DI/HTTP）、Temurin 21（GraalVM JS 以独立 Maven 库引入，需 `-XX:+EnableJVMCI`）、Netty 4（v83 协议字节级不动）、Flyway、log4j2、Bucket4j（限流）、LangChain4j（AI）、Web 控制台（JavaFX 明确移除）。

## 硬性编码约束（红线摘录）

1. **v83 协议字节级兼容**：`net.encryption` + `RecvOpcode` 原样移植。
2. **MySQL `newmaple` 库兼容**：表结构不改，扩展走 migration `ADD COLUMN`（禁止单条 ALTER 串接多列）。
3. **游戏对象不进容器**：Character/MapleMap/Item 手动 new，容器只管基础设施 + Service。
4. **HTTP 与游戏 Netty 隔离 EventLoop**：第三方 API 流量不挤占游戏 tick 线程。
5. `accounts.banned` 只有值 `1` 明确表示已封禁，查询未封禁必须用 `banned <> 1`（兼容 NULL）。
6. 日志用 log4j2 `log.error("描述", e)`，禁用 `e.printStackTrace()`。
7. 全限定类名必须 import 后用短名（除非类名冲突）。
8. **可替换层不得引用稳定层具体类**（用接口或数据投影，防 classloader 换代后 CCE）；**不得持有跨操作状态**。
9. **贡献点（PacketHandler/HTTP/Script/Task/Event）从第一天版本化**——可装卸即兼容面。
10. **JDK 锁定 Temurin 21**。

## 里程碑

按 `ARCHITECTURE.md` 第十一节推进：M0（骨架+基础验证+热更新地基）→ M1（协议+Netty）→ M2（游戏逻辑重写，参考项目作 parity 真值）→ M3（HTTP+AI+渐进重载）→ M4（插件+热更新 L1-L4）→ M5（Web 控制台+迁移）→ M6（分布式）。当前应聚焦 **M0**。
