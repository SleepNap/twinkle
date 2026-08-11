# twish 能力面对接

twinkle 通过 `/api/v1` 向独立客户端 twish 提供 Tool-first 能力面。v0.1 以 twish 仓库的 `docs/server-tasks/mvp-01-capability-auth-readonly.md` 和 `docs/contracts/mvp-readonly-tools.md` 为契约真值。

当前闭环：

```text
Bearer Credential
→ GET /api/v1/identity/me
→ GET /api/v1/capabilities
→ GET /api/v1/capabilities/{toolId}
→ POST /api/v1/tool-executions
→ GET /api/v1/tool-executions/{executionId}
→ Result Envelope + auditRef
```

首批 Tool：

| Tool | scope | 风险 | 数据来源 |
|---|---|---|---|
| `server.health.read@1.0.0` | `server.health:read` | `read` | `HealthRegistry` 安全映射 |
| `player.online.list@1.0.0` | `player.online:read` | `sensitive_read` | `OnlinePlayerMirror` 只读快照 |
| `player.inventory.read@1.0.0` | `player.inventory:read` | `sensitive_read` | 频道内存态背包真值，经 `AdminService`/RPC 纯 DTO 投影 |
| `server.agent.investigate@1.0.0` | `ai:use` | `sensitive_read` | 服务端 Agent + 只读取证工具 |
| `server.agent.conversation.close@1.0.0` | `ai:use` | `read` | Subject 隔离的会话记忆 |

在线玩家 Tool 按角色 ID 稳定排序，支持 1–200 条分页。Cursor 使用 HMAC 防篡改，并绑定 Subject、Credential、`serverId`、快照版本和有效期；镜像变化后旧 Cursor 返回 `409 snapshot_changed`。

在线背包 Tool 输入正整数字符串 `characterId`，只读取当前频道在线角色，不回退到可能过期的数据库存档。输出按背包类型、槽位稳定排序，包含五类背包的精确实例字段、装备扩展与宠物状态；角色不在线返回 `404 resource_not_found`。该 Tool 在 single 下直读频道服务，在 split 下走同一 `AdminService` RPC 契约。

Agent Tool 仅在 `twinkle.ai.enabled=true` 时标记为 `available`；关闭时仍可发现，但状态为
`unavailable`，执行返回 `503 tool_unavailable`。twish 应先读取目录状态，再通过统一
`POST /api/v1/tool-executions` 调用，不依赖 `/api/v1/ai/*` 原始管理路由。调查输出包含
模型标识、实际执行工具、取证 `auditRefs` 和 Token 数；会话按 Subject 隔离。

## twish 对接完成度（2026-08-11）

下表区分“twinkle 已提供”与“twish Desktop 已消费”。服务端出现在 Capability 目录中，
不等于 Desktop 已有可用入口。

| 能力 | twinkle 服务端 | twish Desktop | 结论 |
|---|---|---|---|
| Bearer Credential、身份快照、scope 展示 | 已支持 | 已支持连接检测与展示 | 已闭环 |
| Capability 目录及 `availability` | 已支持五个 Tool | 已读取通用目录 | 已闭环 |
| `server.health.read` | 已支持 | 已支持任务执行与结果展示 | 已闭环 |
| `player.online.list` | 已支持 | 已支持任务执行与结果展示 | 已闭环 |
| `server.agent.investigate` | 已支持发现、执行、审计和 Subject 隔离 | 尚无任务类型、调用分支和 UI 入口 | **未闭环** |
| `server.agent.conversation.close` | 已支持 | 尚未保存、传递和关闭 `conversationId` | **未闭环** |
| Agent 结果字段 | 返回 `reply`、`model`、`executedTools`、`auditRefs` 和 Token 数 | 尚无专用 schema、持久化和展示 | **未闭环** |
| Agent 不可用处理 | 目录返回 `unavailable`，执行返回 `503 tool_unavailable` | 尚无禁用入口、重试语义和专用提示 | **未闭环** |
| `ai:use` Credential | 服务端可签发并逐次鉴权 | 尚无 Agent 权限引导与缺权提示 | **未闭环** |
| 跨仓联调 | 服务端单元与 HTTP E2E 已覆盖 | 尚无 twish 单测及真实跨仓 E2E | **未闭环** |

twish 当前的 `TwinkleClient` 虽然已经具备通用目录读取和
`POST /api/v1/tool-executions` 请求底座，但执行函数仍只接受服务器概览、健康状态和在线
玩家三类任务，并硬编码前两个只读 Tool。完成 Agent 对接至少需要：

1. 增加 Agent 调查与关闭会话的共享契约、Runtime 请求和任务输出类型；
2. 从目录读取 input/output schema 和 `availability`，不要只靠硬编码 Tool ID；
3. 持久化服务端返回的 `conversationId`，后续 Turn 复用，结束时调用关闭 Tool；
4. 展示模型、工具调用、审计引用、Token 数和 `tool_unavailable` / 缺 scope 状态；
5. 增加客户端契约测试及启用/禁用 Agent 两种真实跨仓联调。

在上述事项完成并验证前，不得把“twinkle 已暴露 Agent Tool”表述为“twish 已支持服务端
Agent”。

## 服务端身份

生产环境必须显式配置：

- `TWINKLE_SERVER_ID`：稳定权限资源 ID，不随展示名改变。
- `TWINKLE_SERVER_NAME`：展示名。
- `TWINKLE_SERVER_ENVIRONMENT`：`development / test / staging / production`。
- `TWINKLE_SERVER_VERSION`：可选安全版本字符串。
- `TWINKLE_CURSOR_SIGNING_KEY`：至少 32 字节的 Cursor HMAC 密钥。

## 首次签发

1. 在仅 loopback 可达的启动环境设置至少 32 字符的 `TWINKLE_API_BOOTSTRAP_KEY`。
2. 用 bootstrap Credential 调用 `POST /api/v1/auth/keys`，为 twish 签发只读 key。
3. 安全保存响应中的 `token`；数据库只保存不可逆摘要，之后无法找回明文。
4. 签发日常管理 Credential 后，从正式运行环境移除 bootstrap key 并重启。

```bash
curl -X POST http://127.0.0.1:8080/api/v1/auth/keys \
  -H "Authorization: Bearer $TWINKLE_API_BOOTSTRAP_KEY" \
  -H "Content-Type: application/json" \
  -d '{"displayName":"twish-readonly","scopes":["server.health:read","player.online:read","player.inventory:read","ai:use"]}'
```

API Key 绑定 Subject 与当前 `serverId`。非 bootstrap 签发者只能创建自身 scope 和有效期的子集，不能借 `keys:manage` 扩权或跨服签发。支持禁用、恢复、吊销和轮换；轮换后旧秘密立即失效。

## 调用与错误

正式绑定统一使用：

```http
Authorization: Bearer <credential>
X-Request-Id: req_...
X-Contract-Version: 0.1
```

响应使用稳定 Error Envelope，主要错误码包括 `invalid_input`、`unauthenticated`、`permission_denied`、`resource_not_found`、`snapshot_changed`、`rate_limited`、`tool_unavailable` 和 `internal_error`。客户端必须依据 `error.code` 与 `retryable` 处理，不解析自然语言错误。

所有受保护 HTTP 调用写入 `api_request_audit`。成功 Tool 调用另写 `tool_execution_audit`，并在结果中返回 `auditRef`；审计不保存 Credential、完整玩家列表或模型隐藏推理。相同 Subject、`requestId` 和 Tool 的短期重复调用返回原结果，不重复执行或生成成功审计。

机器可读契约：`GET /api/v1/openapi.yaml`。第三方 IM、写 Tool、审批和双人授权不在 v0.1 只读闭环内。
