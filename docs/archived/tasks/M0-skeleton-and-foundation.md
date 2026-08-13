# M0：骨架与基础设施

> 对应 [ARCHITECTURE.md](../../ARCHITECTURE.md) 第二节（技术选型）、第三节（工程结构）、第四节 4.2（角色）、第五节 5.2/5.3/5.4（热更新地基）、第六节（数据层）、第九节（2C2G 红线）、第十一节 M0。
>
> 状态：未开始 ｜ 前置依赖：无 ｜ 影响模块：全部（公共底座优先）

## 目标

搭起整个工程的骨架与地基，把关键技术可行性全部验证一遍，为后续里程碑铺路。M0 是最大也最重要的一个任务——红线（2C2G）、热更新地基、数据层在此固化。

**交付**：骨架 + Micronaut + 多模块 + MyBatis-Flex 连 SQLite（低配默认）与 PG（大服）双验证 + 配置门面 + Flyway + 热更新地基（接口化/事件化/模块 classloader/RestartCoordinator 状态机）+ 状态/逻辑分离边界 + 2C2G 内存/启动预算基线 + parity 测试基建 + Temurin 21 + GraalJS 全速（EnableJVMCI）验证 + SQLite 100 人负载实测 + 增量 FLUSH 重开实测。

## 任务清单

### 1. Maven 多模块工程

- [x] 父 pom：JDK 21（Temurin）、依赖版本统一管理
- [x] 公共底座模块：`bootstrap`、`core`、`net-netty`、`net-packet`、`data`、`db-dialect`、`plugin-api`
- [x] 游戏域与管理侧模块占位：`domain-game`、`domain-script`、`wz-provider`、`channel`、`coordinator`、`login`、`admin`、`http-api`、`ai`
- [x] 依赖单向无环，管理侧**不得依赖 domain-game**（用 ArchUnit 架构测试固化，bootstrap 模块 4 条规则通过）
- [x] `org.gms` 包根

### 2. bootstrap 装配

- [x] 唯一 main，读 `--profile` 装配角色模块（TwinkleApplication）
- [x] 支持 `--profile=single`（默认）/ `standalone` / `split-channel` / `split-realm`（BootstrapProfile 枚举）
- [x] Micronaut 4.10 启动 + 编译时 DI（实测启动 648ms）
- [ ] HTTP 与游戏 Netty 隔离 EventLoop（红线，M1 落地，此处预留接线）

### 3. 配置门面 + L1 热更新地基

- [x] 配置服务：读 `param_conf`（DB 真值）+ 持有版本号（DbConfigFacade）
- [x] 版本号 + EventBus 广播，订阅者重读（InProcessEventBus + ConfigChangeEvent）
- [x] 验证：改 DB 中配置 → 订阅者热生效（DbConfigFacadeTest 覆盖 upsert→广播→重读）

### 4. 数据层（data + db-dialect）

- [x] `data`：MyBatis-Flex Mapper + Repository + **自研迁移器**（M0 决策：Flyway 社区版不支持 SQLite，见 ARCHITECTURE.md 6.2）
- [x] `db-dialect`：DbDialect 接口 + SQLite/PG/MySQL 三实现 + DbDialectRegistry（按 URL 自动选）
- [x] 业务代码不允许出现裸的方言差异（方言过滤逻辑 MigrationRunnerDialectFilterTest 固化）
- [x] SQLite 三件套：WAL + busy_timeout + foreign_keys（PRAGMA，DataSourceFactory）；单写执行器 M1 落地
- [x] 双库验证：SQLite（DbConfigFacadeTest）+ PG（PostgresIntegrationTest，docker 实测通过）
- [x] 迁移：自研迁移器，版本表 + 启动时跑（MigrationRunner，V1 实测应用成功）
- [x] seed：基础数据随版本发布，migration 管结构、seed 管内容（V1 内 INSERT 5 项）

### 5. EventBus（进程内实现，接口打底）

- [x] `core` 内进程内 EventBus（函数调用），接口不假设进程内外（InProcessEventBus，target 精确匹配）
- [ ] 网络实现留待 M6，接口不变

### 6. 热更新地基

- [x] 模块 classloader（可重载逻辑隔离，状态不进该 classloader）（ReloadableClassLoader，child-first + 稳定层父优先）
- [x] RestartCoordinator 状态机（DRAINING → 增量 FLUSH → 重启 → 恢复）（测试覆盖正常/失败/复位路径）
- [x] 状态/逻辑分离边界：稳定层（协议/数据模型/状态索引/核心机制）vs 可替换层（逻辑系统），包/模块划分落地
- [x] 架构测试：可替换层不得引用稳定层具体类（防 CCE）、不得持有跨操作状态（ArchUnit 4 规则）

### 7. 2C2G 内存/启动预算基线

- [x] JDK 调优：AppCDS + SerialGC + 收紧堆（预算文档 docs/budget-2c2g.md 落基线）
- [ ] 内存预算校准（2G 总账）：堆 1G / 堆外 256M / 原生 512M / 系统余量 256M
- [ ] 启动耗时基线写入验收项（实测 648ms，待 2C2G 严格验证）

### 8. parity 测试基建

- [ ] 录包回放框架（M2 对参考项目作 parity 真值用）
- [ ] 双跑对照框架（M2 用）

### 9. GraalJS 全速验证

- [x] `domain-script` 引入 GraalVM JS 独立库（`org.graalvm.js:js-language` + `truffle-runtime` + `polyglot`，不绑定发行版）
- [x] `-XX:+EnableJVMCI` 全速验证（`-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`，10 万次循环 JIT 路径实测通过）
- [x] 宿主对象契约（cm/qm/em/rm/im）接口化（ScriptEngine 注入宿主对象，M2 落地契约）

### 10. SQLite 100 人负载实测

- [ ] 写延迟 / 锁冲突 / 峰值内存实测（WAL 下预期 70K reads/s + 3.6K writes/s）
- [ ] 确认避开"持续 >10 并发写/秒"软肋（单写执行器 + 低频批量写）

### 11. 增量 FLUSH 重开实测

- [ ] 增量 FLUSH（只刷脏数据，不做全量）
- [ ] WZ 文件缓存（预编译到磁盘，启动读文件省重解析）
- [ ] 上下文恢复（原地复活）
- [ ] 验证：秒级重开 + 不丢档

## 验收标准

- [x] 能启动（`--profile=single` 默认跑通）（实测：Startup 648ms，Server Running）
- [x] 连 SQLite（低配默认）与 PG（大服）双验证通过（SQLite 单元测试 + PG docker 集成测试）
- [x] `param_conf` 热改生效（L1 配置热更新）（DbConfigFacadeTest 覆盖）
- [ ] 2C2G 跑通（内存/启动预算达标）（预算基线已定，2C2G 严格验证待办）
- [x] GraalJS 峰值验证通过（JVMCI 全速 + 宿主对象契约）
- [ ] WZ 文件缓存生效（重开免重解析）（M2 落地）

## 风险与注意

- **2C2G 红线**：任何默认引入重服务、常驻大内存、独立进程的方案都违反此线。缓存/对象/MQ 不默认引入，进程内实现 + 接口打底。
- **游戏对象不进容器**：Character/MapleMap/Item 手动 new（红线 4）。
- **可替换层状态纪律**：逻辑无状态化是热重载成立且不出错的前提，M0 起就按此写。
- **内存态是权威**：DB 只是持久化 + 查询层，热路径不碰库。
