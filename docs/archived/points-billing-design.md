# 积分计费系统设计（points billing）

> 状态：**已落地并归档**。本文是设计快照，与代码不一致时以代码为准。
> 实际实现见 V12/V13 迁移 + `BillingService`；模型键口径与计费点位置已在
> 2026-08-19 修正（V19 迁移 + core `AiGovernanceService` 契约），下文相应段落已同步。

## 目标

给能力面 `/api/v1` 引入**积分额度**：AI 调用与联网搜索按用量消耗积分，账号层共享额度；订阅（plan）用户有三档滚动限额，无 plan 用户纯按积分余额扣；点券/金币/签到提供积分获取渠道。

## 计费模型

- **额度 = 积分**（整数），挂在 `account_records.id`（账号层共享）。同一账号所有 API Key 共享积分。
- **消耗**：`积分 = ceil(inputTokens × inputRate + outputTokens × outputRate)`，`inputRate/outputRate` 来自 `model_rate` 表。
  **模型标识口径 = `provider/modelName`**（`AiModelBundle.descriptor()`，如 `deepseek/deepseek-chat`、
  `local-rule/deterministic`）；早期 seed 误用裸 modelName 导致匹配落空、外部模型静默免费，已由 V19 修正。
  `local-rule/deterministic` rate=0（本地无成本免费）。
- **plan 订阅**：账号订阅 `subscription_plan`，含 monthly / weekly / five_hour 三档积分上限（滚动窗口）；任一窗口超额返回 `429 billing_limit_exceeded`。
- **无 plan**：直接扣积分余额，余额不足返回 `429 insufficient_points`。
- **免计费**：`*` scope 管理员 key（含 bootstrap）免计费；普通 key 无 `ownerAccountId` 调用 AI 拒绝 `billing_account_required`。
- **充值渠道**（本期抽象调账）：点券购买 / 金币购买（每日限）/ 每日签到 / 管理员调账，均落 `point_transaction`，不连真实 `nx_credit/meso`。

## 数据模型（V9 迁移）

四张新表（遵守命名规范：表名 ≥2 词、snake_case、禁关键字）：

1. `point_account` — 积分账户（`account_id` UNIQUE → `account_records.id`、`balance`、`plan_id`、三窗口 `used` + `window_start`）。
2. `model_rate` — 模型倍率（`model_key` UNIQUE、`input_rate`/`output_rate` 整数放大 1e4、`enabled`）。
3. `subscription_plan` — 订阅计划（`plan_code` UNIQUE、三档 `limit`、`price_nx`、`enabled`）。
4. `point_transaction` — 积分流水（`change_amount` 正负、`balance_after`、`reason`、`reference_id`）。

同时 `ai_usage_log` 补 `model` / `input_tokens` / `output_tokens` / `points_cost` 列用于观测。结构 DDL 与 seed 分两个迁移。

## 关键挂载点

- **BillingService**（http-api 模块 `org.gms.httpapi.billing`）：`precheck` / `charge` / `adjust` / `purchase` / `signin` / `balance`。
- **唯一计费点**：`AiFacade.investigate()`（ai 模块）。AI 入口实际有三条——能力面
  `server.agent.investigate`、`/api/v1/ai/chat`、游戏内 `@gm`——本设计初稿误以为只有能力面一条，
  导致 `/api/v1/ai/chat` 长期完全不计费（2026-08-19 修复）。现由 core 契约
  `AiGovernanceService`（`precheck` / `settle`）在 AI 门面内部统一执行，三条入口自动覆盖；
  `ToolExecutionService` 不再自行扣费，只把额度拒绝映射成 429。
- **联网搜索**：`WebSearchTool`（ai 模块，接 Tavily，`twinkle.ai.websearch.provider=tavily|off`），按次扣固定积分（`executedTools` 含 `web_search` 的次数）。

## 管理 API

`BillingAdminController`（`/admin/v1/billing/*`）：账号额度列表/详情、调账、流水、plan CRUD、倍率 CRUD；另加 `/admin/v1/accounts?query=` 供签发 key 时批量选择账号。

## 范围诚实标注

- 点券/金币购买、签约为**抽象调账**（落流水 + 每日限校验），不读写真实 `nx_credit/meso`，真实货币扣减留后续里程碑。
- 联网搜索默认接 Tavily（免费 1000 次/月）；`provider=off` 时工具不装配、不计费。
