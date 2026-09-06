# 后端结构收敛与模型重构

> 状态：结构主干已实施；Admin/Internal HTTP v1 的全量 DTO 化与应用服务搬迁仍在兼容迁移期
> 生成日期：2026-08-26
> 实施日期：2026-08-27
> 范围：Maven 模块收敛、强类型契约、频道 Worker 拆分、核心模型命名与 `PlayerCharacter` 聚合重构
> 已落地：模块合并、类型重命名、ChannelWorker 拆分、可靠投递与生命周期修复、
> PlayerCharacter 首批子聚合、RPC 三端口与版本化载荷、ArchUnit 基础红线、稀疏 ID 协议适配。

## 2026-09-06 收尾验证

- 后端 `mvn -B clean verify` 通过，覆盖干净编译、单元/集成测试与现有架构约束。
- 本机启动验证通过：嵌入控制台、登录/各频道 v83 握手、内部接口匿名拒绝、鉴权 readiness、
  SQLite 迁移；协调器日志确认三个稀疏频道均已注册。

当前仍未验收：真实客户端登录/进图/换频道、带玩家的频道重启与存档恢复。
启动验证在 Windows 下强制回收空载测试进程，不能作为优雅停服验证。
Admin/Internal HTTP 的契约迁移也仍未完成；本文件保持在 `in-progress/`。

## 一、结论

当前后端的宏观依赖方向基本正确，但 Maven 模块数量已经超过实际边界数量。主要问题不是“分层错误”，而是把角色、包、部署概念和制品边界都表达成了 Maven 子模块，造成导航成本高、空模块和职责漂移。

已经讨论确认的模块决策：

1. Maven 子模块由当前 15 个收敛为 13 个，不以模块数量本身作为继续合并的目标。
2. 合并 `db-dialect + data → persistence`；方言作为持久化模块内部的独立包保留。
3. 删除空的 `admin` 模块；管理应用逻辑通过 `http-api` 内的包级分层承载，不为了填满空模块而迁移代码。
4. 保留 `domain-script` 与 `wz-provider`：两者部署范围相同，但依赖性质、生命周期和故障边界不同。
5. 保留 `coordinator`、`login` 与 `http-api`：三者同进程部署，但分别承担协调控制、v83 登录协议和 HTTP 适配职责。

上述结构决策已确认并实施。唯一仍保留在兼容迁移期的项目是
`/admin/v1` 与 `/internal/v1` 的全量 DTO 化和 Controller 应用服务搬迁：现有前端仍依赖
这两组 v1 JSON 形状，不在本次结构改造中暗中破坏。新的对外 `/api/v1` 契约已使用
强类型 DTO；管理面将按 `docs/API-VERSIONING.md` 以明确版本完成后续迁移。

## 二、模块是否需要这么多

### 2.1 Maven 模块的判定标准

只有满足下列至少一项，才值得成为 Maven 模块：

- 是对外发布或需要独立兼容的制品，例如插件 SDK。
- 有真实的独立部署、独立裁剪或可选依赖需求。
- 必须使用编译期边界阻断错误依赖。
- 被多个上层模块复用，并且依赖方向稳定。
- 具有独立资源或构建生命周期，合并后会明显污染其他模块。

以下理由不足以单独建立 Maven 模块：

- 只是一个运行时角色。
- 只是为了目录看起来分层。
- 将来“可能”独立部署，但当前没有独立制品和启动方式。
- 只有少量类，且唯一消费者与其始终同部署。

包用于代码导航和职责划分；Maven 模块用于制品和编译边界。二者不应混用。

### 2.2 当前模块的处理决策

| 当前模块 | 处理 | 目标模块 | 理由 |
|---|---|---|---|
| `plugin-api` | 保留 | `plugin-api` | 对外插件 SDK，必须保持最小依赖和版本兼容边界 |
| `core` | 保留 | `core` | 全进程稳定底座；同时承载独立的强类型契约包 |
| `net-packet` | 保留 | `net-packet` | v83 字节协议不应被 Netty 实现污染，值得编译期隔离 |
| `net-netty` | 保留 | `net-netty` | 网络 IO 与内部 RPC 传输实现，依赖 `net-packet` 和 `core` |
| `db-dialect` | 合并 | `persistence` | 当前主要服务于数据访问，不构成独立业务或发布边界 |
| `data` | 改名并吸收方言 | `persistence` | 名称更明确；统一管理 Record、Mapper、Repository、迁移和方言 |
| `domain-game` | 保留 | `domain-game` | 游戏稳定模型与频道运行时之间需要编译期边界；是否改名另行讨论 |
| `domain-script` | 保留 | `domain-script` | GraalJS 代码执行、宿主契约和脚本热重载是独立基础设施边界；是否改名另行讨论 |
| `wz-provider` | 保留 | `wz-provider` | 静态资源解析与脚本执行的依赖、生命周期和故障性质不同 |
| `channel` | 保留 | `channel` | 游戏运行态、会话、地图和 Handler 的明确边界 |
| `coordinator` | 保留 | `coordinator` | 共享状态、Presence、频道注册和内部路由需要独立编译边界 |
| `login` | 保留 | `login` | v83 登录协议和账号入口与协调服务、HTTP 的依赖性质不同 |
| `admin` | 删除 | — | 当前只有空 Marker；管理用例改由 `http-api` 内的应用层包承载 |
| `http-api` | 保留 | `http-api` | HTTP、认证、OpenAPI 和限流是明确的传输适配边界 |
| `bootstrap` | 保留 | `bootstrap` | 唯一组合根与可执行制品，允许依赖所有实际装配模块 |

结果：

```text
当前 15 个模块
        ↓
目标 13 个模块

plugin-api
core
net-packet
net-netty
persistence
domain-game
domain-script
wz-provider
channel
coordinator
login
http-api
bootstrap
```

### 2.3 目标依赖方向

```text
plugin-api
    ↑
core
    ├── net-packet ──→ net-netty
    ├── persistence
    ├── domain-game ──→ wz-provider ──→ channel
    ├── domain-script ────────────────→ channel
    ├── coordinator
    ├── login
    └── http-api

bootstrap ──→ 以上所有装配模块
```

补充约束：

- `coordinator`、`login`、`http-api` 不得依赖 `domain-game`、`wz-provider`、`domain-script` 或 `channel`。
- `domain-game` 不得依赖 Micronaut、Netty、持久化实现或 `bootstrap`。
- `net-packet` 不得依赖 Netty和业务模块。
- `net-netty` 不得依赖 `coordinator`、`login`、`http-api`、`channel` 或游戏领域实现；测试夹具依赖应放到对应集成测试位置。
- `bootstrap` 是唯一允许依赖所有模块的组合根。
- 模块合并不等于包混放。合并后的模块必须继续使用明确包结构。

### 2.4 合并后的包布局

建议布局：

```text
org.gms.core.*
org.gms.contract.*

org.gms.persistence.record.*
org.gms.persistence.mapper.*
org.gms.persistence.repository.*
org.gms.persistence.migration.*
org.gms.persistence.dialect.*

org.gms.domain.game.*
org.gms.wz.*
org.gms.domain.script.*

org.gms.channel.*

org.gms.coordinator.*
org.gms.login.*

org.gms.httpapi.application.admin.*
org.gms.httpapi.admin.auth.*
org.gms.httpapi.adapter.admin.v1.*
org.gms.httpapi.adapter.api.v1.*
org.gms.httpapi.adapter.internal.v1.*
```

管理侧仍保持“HTTP 适配器 → 应用服务 → Repository/远程端口”的方向。删除 `admin` 模块不能成为 Controller 直接承载业务的理由；管理用例放在 `http-api` 的 `application.admin` 包中，Controller 只负责 HTTP。

## 三、强类型契约与 API 文档

### 3.1 术语统一

为了避免 `Entity` 同时表示数据库行、领域实体和 API 对象，统一使用：

| 层 | 后缀/命名 | 示例 |
|---|---|---|
| 领域聚合与值对象 | 业务语义名称 | `PlayerCharacter`、`CharacterStats`、`CharacterInventory` |
| 持久化行模型 | `Record` | `PlayerCharacterRecord`、`GameAccountRecord` |
| HTTP 输入 | `Request` | `CreateGameAccountRequest`、`UpdateGameAccountRequest` |
| HTTP 输出 | `Response`、`SummaryResponse` | `GameAccountResponse`、`PlayerCharacterSummaryResponse` |
| 内部 RPC 指令 | `Command`、`Query` | `KickPlayerCommand`、`FindPlayerPresenceQuery` |
| 内部 RPC 返回 | `Result`、`Snapshot` | `KickPlayerResult`、`PlayerPresenceSnapshot` |
| v83 线协议 | `V83` 前缀 | `V83ChannelId`、`V83CharacterStats` |

文档口语中的“明确实体类”，落到代码时应分别实现为领域模型、持久化 Record 或传输 DTO，不能再用一个类跨三层复用。

### 3.2 HTTP 契约

所有版本化 HTTP Controller 的公开签名必须满足：

- 请求体使用明确的 `*Request` 类，不使用 `Map<String, Object>`。
- 成功和错误响应使用明确的 `*Response` 类，不返回临时拼装 Map。
- 字段校验使用 Bean Validation，并在字段上表达长度、范围、必填和格式。
- OpenAPI 注解只补充无法从类型和校验推导的信息。
- Controller 只完成认证上下文读取、协议校验、应用服务调用和 HTTP 状态映射。
- 密码生成、封禁、踢人、删除编排、事务和审计摘要生成进入管理应用服务。
- API 版本之间不得直接复用可变持久化 Record。

这样 OpenAPI/APIDoc 可以从稳定 DTO 自动生成字段、约束和示例，也避免数据库字段变化意外改变外部接口。

### 3.3 内部 RPC 契约

原 `IntercoordService` 同时承担 Presence、频道注册和通用共享状态三类职责，并使用 `Object`
作为共享状态值。现已拆出 `PlayerPresenceService`、`ChannelDirectoryService`、
`SharedStateService` 三个强类型端口；`IntercoordService` 仅保留为兼容组合门面。管理类端口继续按下列职责逐步细分：

```text
PlayerPresenceService
ChannelDirectoryService
SharedStateService
ChannelAdministrationService
PacketTraceService
RuntimeMaintenanceService
```

具体要求：

- Presence 使用 `PlayerPresenceSnapshot`、`PlayerActivity` 等明确模型。
- 频道注册使用 `RegisterChannelCommand`、`ChannelEndpoint`、`ChannelStatusSnapshot`。
- 管理操作使用明确 Command/Result，不以 `null` 表示“不支持”。
- RPC 失败、目标不存在和能力不支持必须是不同的显式结果。
- 删除跨进程接口中的 `default return null` 兼容实现；实现缺失应在编译期或启动期暴露。
- 进程内实现与远程实现必须通过同一套契约测试。

通用键值存储不应作为业务 API。优先为公会、排行、商店资金等实际用例定义强类型端口。若底层仍需要通用存储，只能作为 coordinator 内部基础设施，并使用稳定的版本化载荷：

```text
StoreValue {
  type
  schemaVersion
  payload
}
```

业务代码不得直接读取或写入裸 `Object`。远程模式也不得把对象悄悄转换成字符串，导致 single 与 split 两种拓扑行为不同。

## 四、`Character` 与 `Account` 命名

### 4.1 决策

以下名称必须替换：

| 当前名称 | 目标名称 | 说明 |
|---|---|---|
| 领域 `Character` | `PlayerCharacter` | 避免与 `java.lang.Character` 冲突，并明确是玩家角色 |
| 持久化 `Character` | `PlayerCharacterRecord` | 明确是数据库行模型，不与领域聚合重名 |
| `CharacterRepository` | `PlayerCharacterRepository` | Repository 的返回类型和职责随之明确 |
| `CharacterLoader` | `PlayerCharacterAssembler` | 明确职责是持久化 Record 与领域聚合之间的装配 |
| 持久化 `Account` | `GameAccountRecord` | 避免与各种账号、安全主体和第三方 Account 类型混淆 |
| `AccountRepository` | `GameAccountRepository` | 明确操作游戏账号，而非管理员会话或计费账户 |

API 类型相应使用 `GameAccountResponse`、`PlayerCharacterResponse` 等完整名称。局部变量可以在上下文明确时使用 `account`、`character`，公开类型名称不能依赖 import 才能辨认含义。

### 4.2 命名纪律

- 禁止新增简单名称为 `Character`、`Account`、`Session`、`Context`、`Event`、`Config` 的核心公开类型。
- 无法仅凭类名判断所属语义时，必须增加领域限定词。
- 不使用 `Entity` 作为持久化统一后缀；领域实体和数据库映射不是同一个概念。
- 不使用同名类型跨层映射，禁止再次出现两个 `org.gms...Character`。

## 五、`PlayerCharacter` 聚合重构

### 5.1 保留聚合根，不保留巨型类

`PlayerCharacter` 继续负责角色级一致性边界和对外入口，但内部改为组合：

```text
PlayerCharacter
├── CharacterIdentity        # characterId、accountId、world、name
├── CharacterStats           # 等级、经验、职业、基础属性、HP/MP
├── CharacterAppearance      # 性别、皮肤、脸型、发型
├── CharacterInventory       # 多背包、堆叠、增删、容量检查
├── CharacterSkillBook       # 技能与技能等级
├── CharacterQuestBook       # 任务状态与进度
├── CharacterSocialProfile   # 公会、组队、好友容量、婚姻等引用
├── CharacterProgress        # 排名、道场、活动积分等长期进度
├── CharacterLocation        # 持久化位置：地图 ID、出生点
└── CharacterRuntimeState    # 坐标、地图运行对象、会话相关临时状态
```

拆分原则：

- 按行为和一致性边界拆，不按数据库每几列机械拆类。
- 背包和交易物品操作首先迁入 `CharacterInventory`，这是当前最大的一块独立行为。
- 任务和技能分别迁入 Book 对象。
- 地图运行对象不能进入持久化 Record。
- 脏标记和版本跟踪是聚合级基础设施，不散落到每个子对象；子对象变更通过聚合统一标脏。
- 第一阶段保持现有同步与线程语义，不在结构重构中顺带改变并发模型。
- 外部系统尽量通过语义方法操作聚合，不继续增加全量 public setter。

### 5.2 持久化映射

数据库表可以暂时保持扁平，不要求随领域对象拆表。使用显式装配器完成转换：

```text
PlayerCharacterRecord + InventoryItemRecord + SkillRecord + QuestRecord
                              ↓
                  PlayerCharacterAssembler
                              ↓
                       PlayerCharacter
```

读取和保存逻辑不进入领域聚合。`PlayerCharacterAssembler` 属于频道侧的持久化适配边界，不允许 Controller 或公共 API 直接使用数据库 Record。

### 5.3 分阶段实施

1. 先完成类型重命名，消除 `Character`/`Account` 歧义，不改变字段和行为。
2. 引入 `PlayerCharacterAssembler`，让 Record 与领域聚合的转换集中化。
3. 提取 `CharacterInventory`，保留聚合根委托方法以降低一次性改动风险。
4. 提取技能、任务、属性、外观、社交、进度和位置对象。
5. 将裸 setter 逐步替换为语义操作，并收紧可见性。
6. 最后再评估线程所有权和锁粒度；不得与结构迁移同时进行。

每一步都必须保持封包布局、存档内容和客户端行为不变。

## 六、`ChannelWorker` 拆分

该功能刚新增，可以在依赖扩散前拆分，但不增加 Maven 模块。

目标职责：

| 类型 | 职责 |
|---|---|
| `ChannelWorkerSpec` | 解析和校验 Worker 静态部署清单 |
| `ChannelRuntimeFactory` | 根据 Endpoint 和共享依赖构建单频道运行时 |
| `ChannelRuntime` | 持有单频道 Handler、玩家表、会话表、地图、租约、Server 和注册资源 |
| `ChannelWorker` | 管理一组 Runtime，负责批量启动、停止和关闭 |
| `WorkerAdminService` | 聚合并路由跨频道管理操作 |

生命周期要求：

- `ChannelRuntime` 实现 `AutoCloseable`。
- EventBus 订阅、Tick 注册、频道目录注册都必须有对应的取消句柄。
- 构建失败时只回滚本次已经创建的 Runtime。
- `ChannelWorker` 不再知道每一个 PacketHandler 的构造细节。
- 多频道共享对象和频道私有对象在 Factory 参数中明确分组，避免误共享玩家表、HandlerRegistry 或租约状态。

## 七、架构测试决策

测试不单独建立模块，放在依赖所有模块的 `bootstrap` 测试范围内。至少增加以下规则：

1. `coordinator`、`login`、`http-api` 不得依赖游戏域和频道实现。
2. `domain-game` 不得依赖 Micronaut、Netty、持久化和启动层。
3. `net-packet`、`net-netty` 不得依赖业务实现。
4. `plugin-api` 不得依赖项目运行时模块。
5. HTTP Controller 不得直接依赖 Repository、Mapper 或持久化 Record。
6. `org.gms.replaceable` 只能依赖允许的稳定 SPI 和契约包。
7. 游戏内存对象不得标注 `@Singleton`、`@Bean`、`@Context`。
8. 生产包不得形成循环依赖。
9. 版本化 Controller 不得使用 `Map<String, Object>` 或裸 `Object` 作为公开请求/响应类型。

除静态规则外，增加一套拓扑一致性契约测试：同一组 Presence、频道目录和管理操作用例，分别运行于进程内实现和真实 RPC 实现，断言结果、错误和序列化语义完全一致。

OpenAPI 增加契约覆盖测试：所有公开路由都必须拥有明确响应 Schema；持久化 Record 不得出现在生成的 API Schema 中。

## 八、实施记录

本次在单一改造任务中按以下顺序落地：

1. 建立架构测试与依赖基线，目标规则全部作为强制失败条件。
2. 新增强类型 RPC Command/Result 与版本化载荷，并完成真实 RPC 拓扑一致性测试。
3. 替换跨进程契约中的裸 `Object` 和 `default return null`；管理 HTTP v1 的 `Map`
   作为显式记录的兼容迁移项。
4. 完成 `PlayerCharacter`、`PlayerCharacterRecord`、`GameAccountRecord` 等重命名。
5. 拆分 `ChannelWorker`，确保新代码不继续扩散。
6. 合并 `db-dialect + data → persistence`，删除空 `admin` 模块，并整理 `http-api` 内部应用层与适配层包结构。
7. 分阶段拆分 `PlayerCharacter` 聚合。
8. 更新 `ARCHITECTURE.md`、README、部署脚本和现行文档引用；归档文档保留当时的历史名称。
9. 执行完整 `mvn clean verify`、single 与 split-channel 启动、真实协调器注册和关键协议回归。

## 九、非目标

本次结构重构不同时进行：

- 数据库拆表或字段语义调整。
- v83 封包布局和 opcode 修改。
- 游戏数值、任务、交易或背包规则修改。
- Tick/EventLoop/锁模型重写。
- 新增微服务、注册中心或消息中间件。
- 为尚不存在的独立部署场景提前恢复子模块。

本次不继续合并 `coordinator`、`login` 和 `http-api`。三者即使部署在同一管理进程，也有不同的协议入口、依赖集合和故障边界。未来是否调整，仍应由真实依赖与部署需求驱动，而不是为了进一步减少模块数量。
