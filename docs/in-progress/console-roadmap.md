# Twinkle Web 控制台功能路线图

> 状态：持续维护
>
> 原则：参考成熟游戏服务后台的能力覆盖，但所有页面必须接真实服务契约，不以模拟数据冒充完成。

## 2026-08-19 已完成增量（4.2 AI 权限与预算）

- 策略维度定案：挂**账号维度**（`account_records.id`），与积分账户同口径；`subjectId` 因继承签发者会塌缩成「全体一份」，不可用。
- `AiPolicyService` 分层判定 + `ai_usage_policy` 表（V20 三方言）+ 运行期全局开关（V21 seed）。
- 管理员凭据免计费但受全局策略约束；拒绝码按性质分流 403/429/503。
- `ai_usage_log` 的 `points_cost`/`account_id` 回填，并补 `(account_id, created_at)` 索引。
- 新增 `/admin/v1/ai/*` 契约、权限点 `admin.ai:manage` 与控制台 `/ai-policies` 页面。
- 详见下方 4.2 节（含诚实标注）。

## 2026-08-18 已完成增量（安全顺序 1/2/3 落地）

- 控制台强鉴权 + 管理员会话：`/admin/v1` 挂 `AdminAuthFilter`（`@Filter("/admin/v1/**")`），管理员账号（`account_records`）BCrypt 登录 + DB session token（`admin_session`，只存 SHA-256 摘要）。
- 可配置角色表 RBAC：`admin_role` + `account_admin_role`，内置 `super_admin`/`operator`/`auditor`；`AdminRoleController`（角色 CRUD + 账号角色分配）+ 前端 `roles-page`（角色 CRUD + 账号搜索分配，权限点 checkbox 可视化）。
- 不可抵赖审计：`admin_operation_audit`，写操作强制 `X-Admin-Reason`（缺 reason → 400 前置拒绝），记录操作者 + reason + 结果 + requestId。
- bootstrap 首个管理员：`TWINKLE_ADMIN_BOOTSTRAP_ACCOUNT`/`TWINKLE_ADMIN_BOOTSTRAP_PASSWORD`（账号不存在则创建并授 super_admin）。
- 前端登录页 + admin 会话上下文 + 路由守卫 + 退出登录；`admin.ts`/`billing.ts` 自动带 `Authorization: Bearer`，401 跳登录。

## 2026-08-12 已完成增量

- API Key 支持直接更新 Scope；`ai:use` 可即时授予或收回，保存后自动刷新 `permissionVersion`。
- 新增统一 `BackgroundTaskRegistry`，提供有界执行历史、状态、耗时、安全失败摘要和重试投影。
- 新增 `/admin/v1/tasks*` 与 `/admin/v1/schedules*` 管理契约，以及 shadcn 任务监控页面。
- `AiDailySummaryScheduler` 已作为首个真实任务接入统一注册表；后续任务不再自建控制台状态字段。
- 当前历史为单进程内存投影（最多 200 条）；跨重启持久化、集群聚合和日志分页仍属下一阶段。

## 1. 参考项目说明

当前本机工作区和公开仓库检索均未定位到唯一的 `datas-server` 项目，因此暂不能做逐菜单、逐字段的一一核对。
拿到准确仓库路径或网址后，应把其功能追加到本文的对照表，而不是重做已经稳定的 shadcn 页面框架。

## 2. 已经落地

| 领域 | 页面 / 能力 | 后端契约 |
|---|---|---|
| 运行概览 | 健康状态、频道数、在线人数 | `/admin/v1/health`、`channels`、`online` |
| 频道 | 注册频道、地址、端口、在线数 | `/admin/v1/channels` |
| 在线玩家 | 列表、筛选、踢下线 | `/admin/v1/online`、`kick` |
| 账号角色 | 精确查账号、角色快照、踢下线 | `/api/v1/account/*`、`/admin/v1/kick` |
| 配置中心 | 列表、新增、热改、版本广播 | `/admin/v1/config` |
| 运维操作 | 在途实体、脚本/逻辑重载、重启阶段 | `/admin/v1/reload/*`、`restart*` |
| API Key | 身份预检、签发、禁用、启用、轮换、吊销、Scope 替换 | `/api/v1/identity/me`、`auth/keys` |
| 权限 Scope | 游戏读写、AI、Key、事件等 Scope 选择；`ai:use` 即时收放 | `ApiScopes.SUPPORTED`、`PUT auth/keys/{prefix}/scopes` |
| AI 策略 | 运行态与降级、全局开关与模型白名单、账号级日预算、用量 | `/admin/v1/ai/*` |
| 审计 | API 请求审计、Tool 执行安全摘要 | `/admin/v1/audits/*` |
| 任务监控 | 调度启停、立即运行、执行历史、安全失败摘要、失败重试 | `/admin/v1/tasks*`、`/admin/v1/schedules*` |
| 基础体验 | 中英文、移动端、加载/错误/空状态、确认与 Toast | Web 本地组件层 |

## 3. 后端已有能力，下一步接入 UI

1. **能力目录**：Tool 列表、搜索、详情、所需 Scope、OpenAPI 下载。
2. **Tool 执行记录详情**：按 `executionId` 查询同步执行结果。
3. **AI 运行信息**：模型描述、外部模型状态、调用次数、在线总结报表。
4. **AI 对话诊断**：仅作为受控运维工具，不替代 twish 客户端；必须受 `ai:use` 限制。
5. **插件运行状态**：已有 `PluginManager`，但尚未暴露稳定管理接口。
6. **服务指标**：已有 `Metrics` 内部采集，需要安全的管理查询投影。

## 4. 必须新增后端契约

### 4.1 账号与角色管理

- 账号分页、模糊搜索和筛选，而非仅支持完整账号名。
- 封禁/解封、临时封禁、禁言、强制下线、登录状态修复。
- 角色详情、装备、背包、任务、技能、好友、公会、位置和货币快照。
- 受审计保护的角色修复、传送、发放物品和货币调整。
- 所有写操作必须记录操作者、原因、变更前后摘要和 requestId。

### 4.2 AI 权限与预算 ✅ 已完成（2026-08-19）

**开工前置已决策**：策略挂**账号维度**（`account_records.id`），与积分账户同口径。

原因：`api_key_records.subject_id` 在签发时原样继承签发者，而控制台唯一的签发者是 bootstrap
principal（subjectId 是常量 `subject_owner`），所以控制台签出的每把 Key 的 subjectId 都相同，
挂上去会塌缩成「全体一份」。而现有计费**早已是账号维度**——`BillingAiGovernance` 完全忽略
`subjectId`，走 `credentialId → owner_account_id → point_account.account_id`。
故 roadmap 原建议的 `{subjectId}` 路径参数改为 `{accountId}`。

配套决策：签发 API Key **不强制**绑账号（维持现状）；scope 含 `*` 的管理员凭据**保留免计费**，
但仍受全局策略（总开关、模型白名单）约束——否则管理员 Key 泄漏即等于无限免费调用外部模型。

**前置止血（同日先行完成）**：① `model_rate.model_key` 用裸 modelName、而计费传
`provider/modelName`，外部模型一路静默免费（V19 迁移修正口径）；② `/api/v1/ai/chat` 绕过能力面
完全不计费（计费下沉为 core `AiGovernanceService` 契约，唯一计费点收敛到 `AiFacade.investigate`）。
本次的策略拦截就接在同一个治理契约上，未逐入口接线。

已落地：

- **分层判定**（`AiPolicyService`，http-api）：全局开关 → 全局模型白名单 → 解析计费账号 →
  账号策略（开关/白名单/日调用/日积分/日 Token）→ 余额与订阅计划。
  **前两步必须在解析计费账号之前**，否则管理员凭据在解析处 `return free()` 短路，全局策略对它失效。
- **运行期全局开关**：配置中心 `ai.runtime.enabled` / `ai.allowed.models`（V21 seed）。
  区别于装配期的 `twinkle.ai.enabled`（Micronaut 条件，运行期改不了）。
- **账号策略表** `ai_usage_policy`（V20 三方言）：日限额 `>0` 生效、`0` 不限制，24h 滚动窗口，
  计数器走原子自增 SQL；**无策略行 = 不限制**，保证新表上线不锁死存量账号。
- **拒绝码按性质分流**：额度类 429 可重试；`ai_disabled` 503；`model_not_allowed` /
  `policy_disabled` 403 不可重试（此前两处映射一律硬编码 429）。
- **观测缺口回填**：`ai_usage_log` 的 `points_cost` / `account_id` 不再恒为 0；同时补了
  `(account_id, created_at)` 索引（该表自 V4 建表起无任何索引）。
- 权限点 `admin.ai:manage`（写操作），读端点走 `admin:read`；认证、reason 强制与审计由
  `AdminAuthFilter` 通配自动覆盖。
- 前端 `/ai-policies` 页面：运行态卡片 + 账号策略编辑 + 用量。

已落地契约：

```text
GET  /admin/v1/ai/status                 # 模型/连通性/降级/最近错误/全局开关与白名单
GET  /admin/v1/ai/policies?accountId=
PUT  /admin/v1/ai/policies/{accountId}   # 账号维度，不是 subjectId
GET  /admin/v1/ai/usage?from=&to=&accountId=
```

回归锁在 `AiPolicyE2ETest`（bootstrap，4 例）与 `AiPolicyServiceTest`（10 例）。E2E 已验证
摘掉全局判定即变红。

**诚实标注**：

- `local-rule` 不上报 TokenUsage，token 与扣分恒 0，故 token 维度日预算在本地模型下无法端到端
  验证，只能靠单测锁口径。
- 管理员凭据免计费保留，其用量记 `ai_usage_log` 但 `account_id` 为 0（列是 `NOT NULL DEFAULT 0`）。
- 模型白名单在装配期单模型下语义是「当前模型是否放行」，不是多模型路由选型。
- 日计数器即便用原子自增，precheck 与 settle 两阶段之间仍有窗口，日限额是近似值而非硬上限。
- 策略变更后刷新该账号名下 Key 的 `permissionVersion`，但这**不是生效机制**——治理层每次实时
  查库，写库即生效；刷新只让客户端能力目录缓存失效并让审计带上新 policyVersion。
- `ai_usage_log` 存量行的 `created_at` 是 DB 默认的空格分隔格式，与新写入的 ISO-8601 混存。
- 每 Credential 粒度的预算未做（现为账号粒度，同账号多 Key 共享）。

### 4.3 定时任务与后台任务

基础版已经落地：统一 `BackgroundTaskRegistry`、任务/调度管理契约和控制台页面均已可用，`AiDailySummaryScheduler` 已完成首个真实接入。后续任务必须复用注册表，禁止各自创建控制台状态字段。

统一任务投影至少包含：

```text
taskId, taskType, displayName, source
status: queued | running | succeeded | failed | cancelled
progressCurrent, progressTotal, progressMessage
createdAt, startedAt, completedAt, durationMs
nextRunAt, lastRunAt, schedule
attempt, maxAttempts, cancellable, retryable
requestId, subjectId, errorCode, errorSummary
```

已落地契约：

```text
GET  /admin/v1/tasks?limit=
GET  /admin/v1/tasks/{taskId}
POST /admin/v1/tasks/{taskId}/retry
GET  /admin/v1/schedules
POST /admin/v1/schedules/{scheduleId}/run
PUT  /admin/v1/schedules/{scheduleId}/enabled
```

当前执行历史是最多 200 条的单进程内存投影，失败摘要最多 300 字符，不保存原始任务日志或请求正文。下一阶段再补任务持久化、跨进程/集群聚合、分页筛选、进度、取消与重试次数策略；控制台不得无限拉取服务日志。

### 4.4 常规游戏后台能力

- 公告、活动、事件和定时开关。
- 排行榜、在线趋势、留存、经济产出与消耗统计。
- 公会、组队、好友和聊天审计。
- 商城、兑换码、奖励和邮件发放。
- Item / Mob / Map / NPC / Quest / Drop 等 WZ 与脚本数据检索。
- 脚本、插件、版本和发布记录。
- 数据库迁移状态、备份、恢复演练和存储用量。
- 日志查询、告警、线程/内存/GC/连接和消息总线状态。

## 5. 安全顺序

1. ~~控制台强鉴权与管理员会话~~ ✅ 已完成（2026-08-18）
2. ~~管理员 RBAC 和高风险操作原因字段~~ ✅ 已完成（2026-08-18）
3. ~~API Key、AI 策略和所有 GM 写操作的不可抵赖审计~~ ✅ 已完成（GM 写操作 2026-08-18，AI 策略 2026-08-19）
4. 任务注册表与统一状态机。
5. 再扩展账号写操作、内容管理和运营功能。

`/admin/v1` 已套强鉴权（`AdminAuthFilter`），4.2（AI 预算/策略）已完成；对外暴露前仍需完成
4.1（账号写操作）。
