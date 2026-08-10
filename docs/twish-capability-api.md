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

在线玩家 Tool 按角色 ID 稳定排序，支持 1–200 条分页。Cursor 使用 HMAC 防篡改，并绑定 Subject、Credential、`serverId`、快照版本和有效期；镜像变化后旧 Cursor 返回 `409 snapshot_changed`。

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
  -d '{"displayName":"twish-readonly","scopes":["server.health:read","player.online:read"]}'
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
