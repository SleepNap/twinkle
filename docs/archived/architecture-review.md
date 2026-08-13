# Twinkle 冒险岛 v83 服务端重构架构 — 独立架构评审

**评审人**：高见远（架构师）
**评审对象**：`ARCHITECTURE.md`（2026-08-05 定稿）+ `CLAUDE.md`（仓库骨架约定）
**评审性质**：对架构文档本身的独立评审（代码未编写）
**方法**：已实际通读两份文档全文，对引用的矛盾点逐行核实（ARCHITECTURE.md 共 503 行、CLAUDE.md 共 66 行）。

---

## 总体结论

**有条件推荐（Proceed with Conditions）**。

理由：三铁律自洽、状态/逻辑分离作为热重载与分布式的共同地基判断准确，coordinator 无状态 + 共享状态单一属主的设计规避了"每频道各持一份靠同步"的经典反模式，整体成熟度高于多数同类项目；但存在若干必须在 M0 启动前澄清/修订的硬阻塞点（JDK/JVMCI 与 GraalJS 全速的可行性、版本门与 M2 逻辑重写的时序耦合、安全与可观测性的系统性缺位、双文档漂移），不修订则 M0/M2 会有返工与运行期隐患。

---

## ① 优点（Strengths）

1. **三铁律互相支撑、无内在矛盾**：铁律 3（状态/逻辑分离）是铁律 1（多拓扑）与铁律 2（性能）能够成立的前提；把"多进程"明确为"买隔离卖性能"、默认档 `single` 保性能，是务实且诚实的取舍，避免了"既要又要"的伪架构。
2. **coordinator 无状态 + 共享状态单一属主**：彻底规避了分布式游戏服最常见的"各频道缓存共享状态、靠同步一致"数据发散坑；coordinator 崩溃 = 无状态重启 + 心跳重建，无需主备选举，在进程级故障下自洽。
3. **进程边界是配置而非硬编码，且进程内/进程间共用同一套接口**：这是铁律 1 落地最关键的工程化保障（4.1 表把 `bus.send` / `coordinator.定位` / `channel.load` 的内嵌与网络两种实现并列），方向完全正确。
4. **热更新的"按实体渐进重载"而非"全服原子重载"**：明确点出"交易等长操作跨 tick 切换会丢锁产生复制 bug"，并用"逻辑无状态化"作为安全前提，命中要害；5.1 更点明"游戏 tick 单线程，换点干净——tick 帧边界暂停…无并发执行中的逻辑"，说明安全点判定在单线程 tick 模型下是**机器可判定**的。
5. **SQLite 作为低配默认、接口化按需切真库的定位清醒**：明确"SQLite 单文件不能跨进程共享"→ 多进程/分布式必须换库，套回铁律 1；单写连接串行化从构造上消灭 SQLITE_BUSY 的思路正确。
6. **迁移决策有据**：自研迁移器的理由（Flyway 社区版 V10 撤 SQLite）具体可核实，优于空泛选型。
7. **M0 把"预算从一开始写死""parity 测试基建""GraalJS 全速验证"列入验收**，说明团队意识到"能跑不是重构完再测"，工程纪律意识好。

---

## ② 风险与隐患（Risks，标注严重度）

### High

- **R1｜JDK/JVMCI 与 GraalVM JS 全速的可行性存疑（影响 2C2G 红线与 M0 验收）**
  文档 2.2 / 红线 16 写"Temurin 21（HotSpot 标准发行版）+ `-XX:+EnableJVMCI` → GraalJS 全速；不绑定发行版"。但**库存 Temurin 21（纯 HotSpot OpenJDK）默认不携带 JVMCI/Graal 编译器**：`-XX:+EnableJVMCI` 在纯 Temurin 上要么无效、要么需额外挂 `org.graalvm.compiler`（Graal 编译器）jar 且版本须与 JDK 精确对齐，否则 GraalJS 会以慢速解释模式运行甚至失败。换言之，"全速"实际上要求一个**带 JVMCI 的 JDK**——而带 JVMCI 的发行版基本就是 GraalVM CE for JDK 21（本就基于与 Temurin 同源的 OpenJDK 源码）。所以"锁定 Temurin 21 且不绑定发行版"与"需 JVMCI 全速"存在内在张力：**纯 stock Temurin 21 无法满足 GraalJS 全速**。
  *影响面*：2C2G 预算中"原生 512M（GraalVM JS+JIT）"是按全速引擎估的；若退化为解释模式，脚本吞吐与预编译收益落空，且 M0"GraalJS 全速验证"验收项无法在 stock Temurin 上通过。
  *建议*：把 JDK 决策收敛为二选一并写入文档——(a) **GraalVM Community Edition for JDK 21**（天然带 JVMCI+GraalJS，满足"全速"，但需承认它就是一个特定发行版）；或 (b) stock Temurin 21 + 显式引入 `org.graalvm.compiler` + `-XX:+EnableJVMCI -XX:+UseJVMCICompiler`，并写明版本对齐约束与回退（解释模式）行为。**M0 必须用目标 JDK 实测 GraalJS 编译吞吐，不能停留在"搜索核证"。**

- **R2｜安全维度系统性缺位（GraalJS 沙箱 / 第三方 API 鉴权 / 限流逃逸）**
  全文无安全专章。具体盲点：
  - **GraalVM JS 沙箱**：宿主对象契约 cm/qm/em/rm/im 若未对 `ScriptContext` 做 `allowAllAccess(false)` + 白名单类，脚本可通过 `Java.type('java.lang.Runtime')` 执行任意命令——这是**服务端 RCE 级风险**。文档只提"宿主对象契约不变"，未提沙箱配置。
  - **/api/v1 鉴权**：仅提"限流+版本化"，未提认证（API Key / mTLS / 签名）。第三方接口若无鉴权，等于公开写入口。
  - **/internal/v1（官网转调）信任模型**：未定义"官网"如何被信任（内网？共享密钥？），存在伪造转调风险。
  - **限流逃逸**：Bucket4j 只提用在 /api；游戏 Netty 登录端口无限流（登录/握手 DDoS 面）；/internal 是否限流未定义；限流在被 auth 之前还是之后、按什么维度（IP/Key/路由）未定。
  - **SQL 注入**：MyBatis-Flex 参数化默认安全，但"复杂原生 SQL 兜底"与 db-dialect 动态拼接若处理不当有注入面。
  *建议*：M0/M1 必须补**安全专章**：GraalJS Context 沙箱基线（禁 `Java` 全局、白名单宿主对象）、/api 与 /internal 的认证方案、Netty 登录握手限流、全链路限流维度。**沙箱与鉴权是上线前不可妥协项。**

### Medium

- **R3｜版本门（M3 定）与 M2 游戏逻辑重写的时序耦合（返工高风险）**
  5.3 明确"写路径带版本门（M3 定机制）"，但 M2 就重写全部游戏逻辑。若 M2 落地的写路径没有版本门钩子，M3 必须回头给所有写入口 retrofit 版本门——而热重载正确性（迟到的旧逻辑写操作识别/丢弃/重放）正依赖它。这是典型"地基在后面里程碑才定义、上层先写"的返工陷阱。
  *建议*：**版本门契约（如 `@Versioned` 注解 / 写入口统一经 `VersionGate.check(entity)`）在 M0/M1 即定稿**，M2 逻辑从第一天按版本门感知方式写，M3 只做机制装配。

- **R4｜"single" 与 "standalone" 术语过载（内部不一致）**
  1.1 铁律 1 把单进程拓扑标注为"standalone"；但 4.1 的 profile 里 `single`（全内嵌、性能极致档）与 `standalone`（最低配 SQLite、2C2G/1C1G）是**两个不同的单进程档**。即"standalone"在 1.1 指"单进程"概念，在 4.1 指"低配 SQLite 档"，含义漂移，操作员极易混淆。
  *建议*：统一术语——`single`（全内嵌，可配 PG/MySQL 跑大服）与 `standalone`（SQLite 低配）明确区分，并在 1.1 同步修正措辞。

- **R5｜16C32G/2000+ 基线所用数据库未显式绑定**
  文档说"16C32G 单进程抗 2000+"且"single 是性能极致档"，又规定"SQLite 是低配 standalone 默认、PG 是大服/分布式"。但 single 档在 16C32G 上到底用 SQLite 还是 PG 未写明。鉴于 SQLite 单写连接串行化是吞吐天花板，**2000+ 绝不应跑在 SQLite 上**。若不显式声明，M0 验收可能误用 SQLite 跑大服基线。
  *建议*：在 4.1/6.2 增加**拓扑→DB 映射表**：`single@16C32G → PG/MySQL`；`standalone@2C2G → SQLite`。并明确"SQLite 上限 ≈ 数百人在线（受单写连接约束），2000+ 必须真库"。

- **R6｜coordinator 单点星形拓扑在 M6 跨机阶段的高可用盲区**
  4.6.4 / 红线称"coordinator 单点，无状态+可重建，不需主备选举"。这只在**进程级崩溃**成立：coordinator 进程崩 → 重启 → 频道心跳重报 → 定位表重建。但 M6 跨机时，**coordinator 所在机器宕机 = 整服跨频道通信/定位中断**，直至该机恢复或人工迁移；且"coordinator 地址静态配置"在故障转移时会导致所有频道指向死地址。文档未给机器级 HA 方案（active-standby + 共享 DB/租约、或 VIP/DNS 漂移）。
  *建议*：M6 前定义 coordinator 冗余——至少"共享 DB 真值 + 租约选举/手动 failover 到备用机 + 动态发现（不让频道写死地址）"。明确 RTO 与"机器宕机期间仅损失跨频道功能、单频道仍可玩"的降级声明。

- **R7｜M0 验收项过载（里程碑风险）**
  M0 同时验收：Micronaut 多模块 + SQLite/PG 双连 + 自研迁移器 + 热更新地基 + 状态/逻辑边界 + 2C2G 基线 + parity 基建 + GraalJS 全速 + SQLite 100 人负载 + 增量 FLUSH 重开实测。这是 6+ 个独立验证目标挤在一个"骨架"里程碑，任一卡住都会拖垮 M0。尤其 GraalJS 全速（依赖 R1 的 JDK 决策）与双库验证是重磅项。
  *建议*：把 M0 拆为 M0a（骨架+模块+Micronaut+DI+2C2G 预算+状态逻辑边界+迁移器+双库连通）与 M0b（parity 基建+GraalJS 实测+SQLite 100 人负载+增量 FLUSH 实测），或把"验证类"设为非阻塞 spike。

- **R12｜编译期隔离不足以挡运行期契约风险；CCE 防住靠纪律而非机制**
  - 管理侧不依赖 `domain-game` 只在编译期阻断"直接 import 游戏类"，但运行期仍经 DB/service 接口交互：若 coordinator/admin 自建一份游戏数据 DTO 且与频道侧字段漂移，或某 service 接口返回了"看起来是接口、实质是富游戏对象"的类型，分进程序列化即裂。编译期禁令对此无效。
  - 稳定层/可替换层防 CCE：文档靠"可替换层只引用接口/数据投影"（红线 11）。但 Java 无编译机制强制"replaceable 模块不得 import stable 具体类"——纯约定。CCE 只在 reload 后运行时暴露，难在 CI 发现。
  - 实操可行性：模块 classloader 替换 + 实体手动 new + 稳定层接口访问技术上可行（标准 OSGi-like 隔离），但"手工 new 不进容器"与"DI 容器只管基础设施+Service"（红线 4）需工程约束保证游戏对象不被误注册进 Micronaut 容器（否则 reload 时容器持有的旧 classloader 实例引发 CCE）。
  *建议*：(a) 引入 `contracts`/`api-dto` 共享模块（仅接口+DTO，不含游戏实体类），管理侧与频道侧共同依赖，杜绝各自定义 DTO 漂移；(b) 加 **ArchUnit 架构测试**在 CI 机械强制"replaceable → 只依赖 plugin-api + stable 接口 + contracts，禁止依赖 domain-game 具体类"与"游戏对象不得被 @Singleton/@Bean 标注"；(c) 热重载回归测试覆盖"reload 后旧实体迟到写被版本门丢弃/重放"，把 CCE 与复制 bug 暴露在 CI。

- **R14｜coordinator 崩溃重建 / 频道崩溃 / 网络分区下的失效场景需补边界说明**
  - coordinator 崩溃：持久化队列真值在 DB（共享状态真值），重启后从 DB 重投未 ack 消息、接收方按全局 ID 去重——自洽；但"coordinator 地址静态配置"在机器级故障时导致频道指向死地址（与 R6 同源）。
  - 频道崩溃：该频道玩家掉线；崩溃前在途跨频道消息由 coordinator 队列重投，但发往已掉线玩家的消息随玩家离线丢弃（可接受）；**跨频道交易**若涉两频道，需单一属主账本（公会/商店资金已在 coordinator 属主，OK），但若未来出现"跨频道玩家间交易"需明确走属主账本，文档未声明。
  - 网络分区：频道与 coordinator 失联 → 定位表过期 → 该频道玩家不可被定位/跨频道消息停摆，但频道本地逻辑继续（共享状态单一属主、频道不持副本，故无 split-brain）——设计自洽；可用性下降但一致性保住。需写明"分区期间降级为单频道可玩、跨频道功能暂停"的声明。
  *建议*：在 4.5 补一段"故障矩阵"：coordinator 崩 / 频道崩 / 分区三种下的行为、丢什么、保什么、RTO。

- **R15｜三库方言统一的工程代价被低估**
  文档称"MyBatis-Flex 一份代码跑三库""复杂 SQL 用两库公共子集"。但 SQLite 是方言异类（无原生布尔、无存储过程、时间/字符串函数弱、JSON 支持晚），与 PG/MySQL 的公共子集比"SQLite 子集 vs PG/MySQL 子集"更现实。实际复杂查询仍需按方言分支，db-dialect 的覆盖边界需尽早定义，否则 M2 逻辑里会冒出裸方言。
  *建议*：M0 定义"SQL 能力矩阵"（允许/禁止的 SQL 特性清单），复杂查询强制走 Repository + db-dialect 分支，CI 用 SQL 测试在三库各跑一遍（尤其 SQLite 与 PG 差异点）。

### Low

- **R8｜"恰好一次"术语**：4.5 的"语义恰好一次"实为"幂等至少一次"（持久化队列 + 全局 ID 去重 + 属主序号），非事务性 exactly-once。*建议*：措辞加"（有效一次 / effectively-once）"避免误导。
- **R9｜WAL 随 schema migration 迁移的成本与风险**：5.5 要求"migration 变更 schema 时旧 WAL 也要迁移"，但 WAL 是崩溃恢复日志而非归档，逐条迁移旧格式事件成本高且易错。*建议*：改为"schema migration 时强制 checkpoint + flush + 清空 WAL"，旧 WAL 不迁移（崩溃恢复日志无需跨 schema 保留）。
- **R10｜可观测性/监控/告警/降级/压测 SLO 全缺**：无 metrics（Micrometer/Prometheus）、无 trace、无 health/alert、无降级策略（DB 慢 / coordinator 宕 / GraalJS OOM 时频道如何降级）、无明确性能预算（tick 时间预算、存档延迟、登录延迟 SLI）。2000+ 服务必须可观测。*建议*：补专章 + 把性能预算写进 M0 验收。
- **R11｜parity 真值录制时机**：M2 才"参考项目作 parity 真值（录包回放/双跑对照）"，但 M0 已建 parity 基建。若 M0 只建 harness 不录参考包，M2 开始才录，会延误。*建议*：在 M0/M1 即录制参考项目行为样本，M2 直接有真值可用。
- **R13｜L4 "上下文恢复"机制未定义**：5.4 提"增量 FLUSH + WZ 文件缓存 + 上下文恢复"三件套，但"上下文恢复"指什么、如何在不全量落盘下复活 2000 玩家实时状态未说明。若"恢复 = 从 DB 重载"则非"快"；若"恢复 = 内存快照到共享/本地存储"则需定义快照格式与位置。*建议*：M3 前定义上下文恢复的具体载体（如周期内存检查点 → 内存映射文件/本地 KV，或仅恢复会话元数据 + 从 DB 加载角色），并写明与 WZ 缓存、增量 FLUSH 的协同时序。
- **R16（文档质量）｜部分选型缺"为何不选 X"**：Micronaut vs Quarkus、MyBatis-Flex vs JOOQ 未给对比依据（迁移器与 SQLite 的理由则给得好）。*建议*：补关键取舍的"备选与被否理由"小节，降低后续质疑成本。

---

## ③ 文档矛盾与不一致（Contradictions，已逐行核实）

- **C1（已核实，真实存在）｜Flyway vs 自研迁移器**
  - `ARCHITECTURE.md`：2.1 选型表（line 46）、3 模块结构（line 72 `data/ # ... + 自研迁移器`）、6.2（line 377）三处均写**自研迁移器，替换 Flyway（M0）**，内部自洽。
  - `CLAUDE.md`：模块结构（line 25 `data（MyBatis-Flex/Flyway）`）、技术选型（line 48 列 `Flyway`）两处仍写 **Flyway**。
  - *定性*：**CLAUDE.md 与 ARCHITECTURE.md 真实冲突**。权威源判定：CLAUDE.md 自身 line 11 声明"ARCHITECTURE.md 是唯一权威规范"，故以 ARCHITECTURE.md 为准——迁移器 = 自研，Flyway 不引入。CLAUDE.md 为过期副本。
  - *修订责任*：文档维护者（doc owner）在 M0 启动前修订 CLAUDE.md line 25/48，删除 Flyway、改为"自研迁移器"。严重度 **Medium**（治理/漂移风险：新人不读 ARCHITECTURE 会误引 Flyway 依赖，与 M0 自研决策冲突）。

- **C2（已核实，非独立冲突，需纠正题面）｜"迁移器归属层级不同"**
  题面称"CLAUDE.md 把迁移器归 data 模块写 Flyway，ARCHITECTURE 把它当独立决策点"。**实际核证：ARCHITECTURE.md line 72 同样把迁移器放在 `data` 模块内**（`data/ # MyBatis-Flex 映射 + Repository + 自研迁移器`），并未将其列为独立模块。因此两文档**在"归属 data 模块"上其实一致**，所谓"层级不同"不成立；唯一冲突仍是 C1 的工具名（Flyway/自研）。即**题面所问的两处"矛盾"本质是同一处不一致**，不是两个独立事实冲突。
  - *结论*：C2 应并回 C1，单一修订动作（改 CLAUDE.md 工具名）即可同时消解题面中的"矛盾 1 与 矛盾 2"。

- **C3（补充发现，内部不一致）｜"standalone" 一词两义**：见 R4（1.1 指单进程概念，4.1 指低配档）。
- **C4（补充发现）｜DB 与拓扑映射缺失导致大服基线歧义**：见 R5。

---

## ④ 修订建议（Recommendations，按优先级）

### P0（M0 启动前必须解决，否则返工/运行期隐患）
1. **R1 → JDK 决策收敛**：明确"GraalJS 全速"所需的 JDK 到底是 GraalVM CE for JDK 21 还是 stock Temurin + compiler jar；M0 用目标 JDK 实测编译吞吐，不靠"搜索核证"。
2. **R3 → 版本门契约前移**：在 M0/M1 定稿写路径版本门契约，M2 逻辑按版本门感知编写。
3. **R2 → 安全专章（M0/M1 落地）**：GraalJS Context 沙箱（禁 Java 全局、白名单宿主对象）、/api 与 /internal 认证方案、Netty 登录握手限流、限流维度与顺序。
4. **C1/C2 → 双文档一致性**：修订 CLAUDE.md 移除 Flyway、改自研迁移器；确立"ARCHITECTURE.md 为唯一规范、CLAUDE.md 仅作指针/不重述技术决策"的硬规则，并加 CI 校验（CLAUDE.md 不得出现技术选型词）。

### P1（M0–M2 内解决）
5. **R4 → 术语统一**：single/standalone 区分并修正 1.1 措辞。
6. **R5 → 拓扑-DB 映射表**：`single@16C32G → PG/MySQL`；`standalone@2C2G → SQLite`；写明 SQLite 人数上限。
7. **R6 → coordinator M6 HA**：定义机器级冗余/故障转移与动态发现，明确 RTO 与降级声明。
8. **R7 → M0 拆分**：M0a/M0b 或验证项非阻塞化。
9. **R10 → 可观测性 + 性能预算专章**：metrics/trace/health/alert/降级；tick 与存档延迟 SLI 写入 M0 验收。
10. **R11 → parity 录制前移**：M0/M1 录制参考项目行为样本。
11. **R12 → 契约模块 + ArchUnit**：引入 `contracts`/`api-dto` 共享模块；CI 加架构测试强制分层与"游戏对象不得进容器"；热重载回归覆盖迟到写。
12. **R14 → 故障矩阵**：4.5 补 coordinator 崩 / 频道崩 / 分区的行为、丢失与保留、RTO。
13. **R15 → SQL 能力矩阵**：M0 定义允许/禁止的 SQL 特性，CI 三库各跑。

### P2（后续/不阻塞主线）
14. **R8 → "恰好一次"措辞**补 effectively-once。
15. **R9 → WAL 在 migration 时 flush + 清空**，不逐条迁移。
16. **R13 → 上下文恢复机制**在 M3 前定义载体与时序。
17. **R16 → 关键选型补"为何不选 X"**对比小节。

---

## ⑤ 总体评分

**7.5 / 10**

一句话评语：**主干设计扎实、三铁律自洽、状态/逻辑分离与 coordinator 无状态两大决策命中要害，距"可放心启动 M0"只差四件 P0 必办（JDK/JVMCI 可行性、版本门前移、安全专章、双文档一致性），补齐后即为一流通用游戏服重构蓝图。**

---

## M0 启动前必须澄清/修订清单
- [ ] **JDK 决策收敛**：GraalJS 全速到底用 GraalVM CE for JDK 21 还是 Temurin+compiler jar？M0 实测编译吞吐（R1/P0）
- [ ] **版本门契约在 M0/M1 定稿**，M2 逻辑按版本门感知写（R3/P0）
- [ ] **安全专章**：GraalJS 沙箱、/api 与 /internal 认证、Netty 登录限流（R2/P0）
- [ ] **CLAUDE.md 移除 Flyway 改自研迁移器**；确立 ARCHITECTURE 唯一规范 + CI 校验（C1/C2/P0）
- [ ] **拓扑→DB 映射**：single@16C32G 用 PG/MySQL，standalone@2C2G 用 SQLite，写明 SQLite 人数上限（R5/P1）
- [ ] **single/standalone 术语统一**，修正 1.1（R4/P1）
- [ ] **coordinator M6 机器级 HA 与动态发现**方案（R6/P1）
- [ ] **M0 验收拆分**或验证项非阻塞化（R7/P1）
- [ ] **可观测性 + 性能预算专章**，SLI 进 M0 验收（R10/P1）
- [ ] **parity 参考包在 M0/M1 录制**（R11/P1）
- [ ] **contracts/api-dto 模块 + ArchUnit 架构测试**（R12/P1）
- [ ] **4.5 故障矩阵**（coordinator 崩/频道崩/分区）（R14/P1）
- [ ] **SQL 能力矩阵**，CI 三库各跑（R15/P1）
- [ ] "恰好一次"措辞补 effectively-once（R8/P2）
- [ ] WAL 在 schema migration 时 flush + 清空，不逐条迁移（R9/P2）
- [ ] 上下文恢复机制在 M3 前定义（R13/P2）
- [ ] 关键选型补"为何不选 X"对比（R16/P2）

---

## 决策记录（Decision Log）

- **2026-08-06 | R1 已采纳（建议 a）**：JDK 由 stock Temurin 21 改为 **GraalVM Community Edition for JDK 21**。
  - *理由*：stock Temurin 21 默认不含 JVMCI / Graal 编译器，`-XX:+EnableJVMCI` 无法在纯 HotSpot 上获得 GraalJS 全速（需额外挂载 `org.graalvm.compiler` 且与 JDK 版本精确对齐，脆弱）；GraalVM CE 21 原生内置 JVMCI + GraalVM JS，满足「全速」前提，且仍基于 OpenJDK / HotSpot（AppCDS + SerialGC 内存调优路径不变）。
  - *已修订*：`ARCHITECTURE.md`（2.1 选型表 / 2.2 关键取舍 / 9.1 内存调优 / 红线 16 / M0 验收项）与 `CLAUDE.md`（技术选型 / 红线 10），删除所有 `EnableJVMCI` 依赖表述。
  - *其余评审项*：R2 安全专章、R3 版本门前移、C1 Flyway 等由项目方评估后暂未采纳，维持原文档表述。

- **2026-08-06 | 二轮评审跟进（A组采纳并落地）**：用户牵头再评估后采纳以下项并落地，全模块测试通过——
  - **R3 版本门前移**：core 新增 `org.gms.hotreload.versioned`（`VersionGate` / `Versioned` / `DefaultVersionGate`），ARCHITECTURE.md 5.3 改为"契约 M0/M1 已定稿、M2 写路径按版本门感知编写"。
  - **C1 双文档一致性**：CLAUDE.md 已删 Flyway（data 模块改"自研迁移器"）；代码本就自研 `MigrationRunner`，无 Flyway 依赖。
  - **R4 术语统一 / R5 拓扑-DB 映射 / R15 SQL 能力矩阵 / R14 故障矩阵**：ARCHITECTURE.md 1.1 / 6.2 / 6.3 / 4.5 补全。
  - **R10 可观测性地基**：core 新增 `org.gms.observability`（`Metrics` + `NoopMetrics` + `Sli` + `HealthIndicator/HealthRegistry/MemoryHealthRegistry` + `MdcKeys`）；HTTP / Micrometer 绑定留 M3 管理进程 HTTP 落地时（本阶段不引 Prometheus）。
  - **安全门槛 M0 收尾**：`.gitignore` 补 secret 模式；`SqlInjectionScanTest`（禁 `${}` 与裸 Statement，`db-dialect`/`data.migrate` 白名单）；注入防御 demo 进 `MyBatisFlexRepositoryTest`；ARCHITECTURE.md 4.2 补"网络平面收敛"。
  - **推迟项**：R1 GraalJS 实测留 M2（引入 domain-script 时）；R2 沙箱/鉴权、R6 coordinator HA、R12 contracts 模块、R11 parity 录包（需客户端环境）按各自里程碑推进。
