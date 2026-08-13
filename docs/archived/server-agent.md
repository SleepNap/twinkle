# 服务端 Agent 使用说明

## 已落地范围

服务端 Agent 是默认关闭的可选能力。启用后有两条入口：

- 游戏内输入 `@gm 你的问题`，由 AI 值班 GM 私下回复，不向地图广播问题；
- 管理 API `POST /api/v1/ai/chat`，供控制台或内部客户端发起带会话的调查。
- twish 通过 Capability 目录发现 `server.agent.investigate` 和
  `server.agent.conversation.close`，统一经 `/api/v1/tool-executions` 调用。

Agent 可自主调用四个只读工具：在线概览、角色存档概况、已落库背包快照、账号状态。
每次工具执行都会写 `tool_execution_audits`，答复返回 `auditRef`。玩家聊天入口只能读取本人
角色，在线概览不暴露玩家名单，账号工具仅管理 API 可用。当前没有交易历史、掉落历史或
在线未落盘背包工具，因此 Agent 必须明确证据边界，不能断言吞物、诈骗、回档或已恢复。

## 启用真实模型

默认 `twinkle.ai.enabled=false`，不会装配模型、会话记忆或 Agent 线程池。生产环境推荐使用
OpenAI-compatible 接口，密钥只通过环境变量注入。

DeepSeek 示例（PowerShell）：

```powershell
$env:TWINKLE_AI_ENABLED='true'
$env:TWINKLE_AI_MODEL_PROVIDER='deepseek'
$env:TWINKLE_LLM_API_KEY='替换为真实密钥'
$env:TWINKLE_LLM_MODEL='deepseek-chat'
mvn -pl bootstrap -am mn:run
```

Ollama/OpenAI-compatible 本地服务示例：

```powershell
$env:TWINKLE_AI_ENABLED='true'
$env:TWINKLE_AI_MODEL_PROVIDER='openai-compatible'
$env:TWINKLE_LLM_BASE_URL='http://127.0.0.1:11434/v1'
$env:TWINKLE_LLM_MODEL='qwen3:8b'
mvn -pl bootstrap -am mn:run
```

`local-rule` 只用于无网络开发和测试，不具备开放自然语言理解能力。远程地址要求配置
`TWINKLE_LLM_API_KEY`；仅 loopback 地址允许空密钥占位。

常用资源上限：

| 环境变量 | 默认值 | 含义 |
|---|---:|---|
| `TWINKLE_AI_MAX_MESSAGES` | 20 | 单会话保留消息数 |
| `TWINKLE_AI_MAX_CONVERSATIONS` | 100 | 进程内最多活跃会话 |
| `TWINKLE_AI_PLAYER_WORKER_THREADS` | 1 | 玩家 Agent 隔离线程数 |
| `TWINKLE_AI_PLAYER_COOLDOWN_SECONDS` | 15 | 同一玩家两次提问的最短间隔 |
| `TWINKLE_LLM_TIMEOUT_SECONDS` | 45 | 单次模型请求超时秒数 |

## 管理 API

请求需要具有 `ai:use` scope 的 API key：

```http
POST /api/v1/ai/chat
Authorization: Bearer <credential>
Content-Type: application/json

{
  "conversationId": "incident-20260811-001",
  "message": "调查 Hero 的已落库背包里是否还有 2000000"
}
```

响应包含 `reply`、`model`、`executedTools`、`auditRefs` 及 token 计数，不返回模型隐藏推理。
完成调查后可调用 `DELETE /api/v1/ai/chat/{conversationId}` 主动释放会话。

## 运行边界

- 工具全部只读；Agent 无封禁、踢人、发物品、改库或执行命令的能力。
- 玩家问题和工具数据均按不可信输入处理；使用摘要计费记录，不保存玩家问题原文。
- 模型调用在独立小线程池执行，不阻塞 Netty 或游戏 tick 线程。
- `single`/`standalone` 已支持玩家聊天闭环。split 拓扑中 AI 仍只在 coordinator 装配，纯
  channel 进程会稳定返回“未启用”；跨进程玩家 Agent RPC 是下一阶段工作。
- 下一批取证工具优先级：交易流水、掉落/拾取流水、在线内存背包只读快照。
