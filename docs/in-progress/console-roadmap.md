# Twinkle Web 控制台功能路线图

> 状态：持续维护
>
> 原则：参考成熟游戏服务后台的能力覆盖，但所有页面必须接真实服务契约，不以模拟数据冒充完成。

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

### 4.2 AI 权限与预算

沿用现有 API Key Scope，不另建互相冲突的权限系统：

- `ai:use`：是否允许调用 AI。
- Tool 自身所需 Scope：限制 AI 最终能触达的游戏能力。
- 每 Credential / Subject 的模型白名单、日预算、调用次数、Token 和费用上限。
- 全局 AI 开关、降级状态、模型连通性和最近错误。
- 策略变更必须产生新的 `permissionVersion` 并写入审计。

建议契约：

```text
GET  /admin/v1/ai/status
GET  /admin/v1/ai/policies
PUT  /admin/v1/ai/policies/{subjectId}
GET  /admin/v1/ai/usage?from=&to=&subjectId=
```

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

1. 控制台强鉴权与管理员会话。
2. 管理员 RBAC 和高风险操作原因字段。
3. API Key、AI 策略和所有 GM 写操作的不可抵赖审计。
4. 任务注册表与统一状态机。
5. 再扩展账号写操作、内容管理和运营功能。

`/admin/v1` 当前只能部署在 loopback / 内网；在强鉴权完成前禁止直接暴露公网。
