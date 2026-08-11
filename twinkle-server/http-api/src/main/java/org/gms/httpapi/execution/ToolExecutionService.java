package org.gms.httpapi.execution;

import org.gms.data.entity.ToolExecutionAudit;
import org.gms.data.repo.ToolExecutionAuditRepository;
import org.gms.httpapi.auth.ApiPrincipal;
import org.gms.httpapi.capability.ToolCatalogService;
import org.gms.httpapi.contract.ApiContract;
import org.gms.httpapi.identity.ServerIdentity;
import org.gms.httpapi.limit.ApiRateLimiter;
import org.gms.observability.Metrics;
import org.gms.service.agent.ServerAgentService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** v0.1 统一 Tool 调用、同步结果缓存、短期去重和权威审计。 */
public final class ToolExecutionService {

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "contractVersion", "requestId", "taskId", "stepId", "toolId", "toolVersion",
            "input", "dryRun", "idempotencyKey", "approvalToken", "clientContext");
    private static final Set<String> CLIENT_CONTEXT_FIELDS = Set.of(
            "locale", "source", "intentSummary");
    private static final Set<String> CLIENT_SOURCES = Set.of(
            "desktop", "web", "server_agent", "local_im", "server_im");

    private final ToolCatalogService catalogService;
    private final ServerHealthTool healthTool;
    private final OnlinePlayerPageService onlineTool;
    private final PlayerInventoryTool inventoryTool;
    private final ToolExecutionAuditRepository auditRepository;
    private final ApiRateLimiter rateLimiter;
    private final Metrics metrics;
    private final ServerIdentity serverIdentity;
    private final ServerAgentService serverAgent;
    private final Object[] executionLocks = new Object[64];
    private final Map<String, StoredExecution> byExecutionId = new ConcurrentHashMap<>();
    private final Map<String, String> executionIdByDedupeKey = new ConcurrentHashMap<>();

    public ToolExecutionService(ToolCatalogService catalogService, ServerHealthTool healthTool,
                                OnlinePlayerPageService onlineTool,
                                PlayerInventoryTool inventoryTool,
                                ToolExecutionAuditRepository auditRepository,
                                ApiRateLimiter rateLimiter, Metrics metrics,
                                ServerIdentity serverIdentity, ServerAgentService serverAgent) {
        this.catalogService = catalogService;
        this.healthTool = healthTool;
        this.onlineTool = onlineTool;
        this.inventoryTool = inventoryTool;
        this.auditRepository = auditRepository;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.serverIdentity = serverIdentity;
        this.serverAgent = serverAgent;
        for (int index = 0; index < executionLocks.length; index++) {
            executionLocks[index] = new Object();
        }
    }

    public ExecutionResult execute(ApiPrincipal principal, Map<String, Object> body,
                                   String fallbackRequestId) {
        pruneExpiredExecutions();
        ExecutionCall call = parse(body, fallbackRequestId);
        String dedupeKey = principal.subjectId() + "\n" + call.requestId() + "\n" + call.toolId();
        synchronized (executionLocks[Math.floorMod(dedupeKey.hashCode(), executionLocks.length)]) {
        Optional<Map<String, Object>> visibleDetail = catalogService.detail(principal, call.toolId());
        if (visibleDetail.isEmpty()) {
            metrics.increment("twinkle.tool.rejected", "code", "resource_not_found");
            throw new ToolProtocolException(io.micronaut.http.HttpStatus.NOT_FOUND,
                    "resource_not_found", "Tool 不存在或对当前凭据不可见", false,
                    null, call.requestId(), Map.of());
        }
        ToolCatalogService.ToolSpec spec = catalogService.executable(
                        principal, call.toolId(), call.toolVersion())
                .orElseThrow(() -> new ToolProtocolException(io.micronaut.http.HttpStatus.BAD_REQUEST,
                        "invalid_input", "不支持的 Tool 版本", false, null, call.requestId(),
                        Map.of("supportedVersion", ToolCatalogService.TOOL_VERSION)));
        if (!spec.available() || ((ToolCatalogService.AGENT_INVESTIGATE_TOOL.equals(call.toolId())
                || ToolCatalogService.AGENT_CLOSE_TOOL.equals(call.toolId())) && !serverAgent.available())) {
            throw new ToolProtocolException(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "tool_unavailable", "服务端 Agent 当前未启用", true, null,
                    call.requestId(), Map.of());
        }
        if ((ToolCatalogService.ONLINE_TOOL.equals(call.toolId())
                || ToolCatalogService.INVENTORY_TOOL.equals(call.toolId()))
                && (call.intentSummary() == null || call.intentSummary().isBlank())) {
            throw invalidInput("敏感读取必须提供 clientContext.intentSummary",
                    call.requestId(), null);
        }
        if (!principal.permits(spec.requiredScope())
                || !serverIdentity.serverId().equals(principal.serverId())) {
            metrics.increment("twinkle.tool.rejected", "code", "permission_denied");
            throw new ToolProtocolException(io.micronaut.http.HttpStatus.FORBIDDEN,
                    "permission_denied", "当前凭据无权执行该 Tool", false, null,
                    call.requestId(), Map.of("requiredScopes", List.of(spec.requiredScope())));
        }
        String duplicateExecutionId = executionIdByDedupeKey.get(dedupeKey);
        if (duplicateExecutionId != null) {
            StoredExecution duplicate = byExecutionId.get(duplicateExecutionId);
            if (duplicate != null) {
                return new ExecutionResult(call.requestId(), duplicate.result());
            }
        }
        if (!rateLimiter.tryConsume("tool:" + principal.credentialId() + ":" + call.toolId())) {
            metrics.increment("twinkle.tool.rejected", "code", "rate_limited");
            throw new ToolProtocolException(io.micronaut.http.HttpStatus.TOO_MANY_REQUESTS,
                    "rate_limited", "当前 Credential 的 Tool 调用频率过高", true,
                    null, call.requestId(), Map.of());
        }

        String executionId = publicId("exec");
        String auditRef = publicId("audit");
        Instant startedAt = Instant.now();
        final Map<String, Object> output;
        try {
            if (ToolCatalogService.HEALTH_TOOL.equals(call.toolId())) {
                if (!call.input().isEmpty()) {
                    throw invalidInput("server.health.read 的 input 必须为空对象",
                            call.requestId(), executionId);
                }
                output = healthTool.read(call.requestId(), executionId);
            } else if (ToolCatalogService.ONLINE_TOOL.equals(call.toolId())) {
                output = onlineTool.page(principal, call.input(), call.requestId(), executionId);
            } else if (ToolCatalogService.INVENTORY_TOOL.equals(call.toolId())) {
                output = inventoryTool.read(call.input(), call.requestId(), executionId);
            } else if (ToolCatalogService.AGENT_INVESTIGATE_TOOL.equals(call.toolId())) {
                output = investigateWithAgent(principal, call);
            } else if (ToolCatalogService.AGENT_CLOSE_TOOL.equals(call.toolId())) {
                output = closeAgentConversation(principal, call);
            } else {
                throw new ToolProtocolException(io.micronaut.http.HttpStatus.NOT_FOUND,
                        "resource_not_found", "Tool 不存在", false, executionId,
                        call.requestId(), Map.of());
            }
        } catch (ToolProtocolException e) {
            throw e;
        } catch (RuntimeException e) {
            Instant failedAt = Instant.now();
            insertAudit(auditRef, executionId, call, principal, spec, startedAt, failedAt,
                    "failed", e.getClass().getSimpleName());
            metrics.increment("twinkle.tool.calls", "tool", call.toolId(), "result", "failed");
            throw new ToolProtocolException(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "tool_unavailable", "服务端 Agent 调查暂时失败", true, executionId,
                    call.requestId(), Map.of());
        }

        Instant completedAt = Instant.now();
        Map<String, Object> result = resultEnvelope(call.requestId(), executionId, output,
                auditRef, completedAt);
        insertAudit(auditRef, executionId, call, principal, spec, startedAt, completedAt,
                "succeeded", null);
        byExecutionId.put(executionId, new StoredExecution(principal.subjectId(),
                spec.requiredScope(), result, completedAt));
        executionIdByDedupeKey.put(dedupeKey, executionId);
        metrics.increment("twinkle.tool.calls", "tool", call.toolId(), "result", "succeeded");
        metrics.record("twinkle.tool.latency", Duration.between(startedAt, completedAt),
                "tool", call.toolId(), "result", "succeeded");
        return new ExecutionResult(call.requestId(), result);
        }
    }

    public Optional<Map<String, Object>> find(ApiPrincipal principal, String executionId) {
        StoredExecution stored = byExecutionId.get(executionId);
        if (stored == null || !stored.subjectId().equals(principal.subjectId())
                || !principal.permits(stored.requiredScope())) {
            return Optional.empty();
        }
        return Optional.of(stored.result());
    }

    private ExecutionCall parse(Map<String, Object> body, String fallbackRequestId) {
        if (body == null) {
            throw invalidInput("请求正文必须是 JSON 对象", fallbackRequestId, null);
        }
        rejectExtraFields(body, ENVELOPE_FIELDS, fallbackRequestId, null, "调用信封");
        String contractVersion = requiredString(body.get("contractVersion"),
                "contractVersion", 16, fallbackRequestId, null);
        if (!ApiContract.VERSION.equals(contractVersion)) {
            throw invalidInput("不支持的 contractVersion", fallbackRequestId, null);
        }
        String requestId = requiredString(body.get("requestId"), "requestId", 128,
                fallbackRequestId, null);
        if (!requestId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw invalidInput("requestId 格式无效", fallbackRequestId, null);
        }
        String taskId = optionalString(body.get("taskId"), "taskId", 128, requestId, null);
        String stepId = optionalString(body.get("stepId"), "stepId", 128, requestId, null);
        String toolId = requiredString(body.get("toolId"), "toolId", 128, requestId, null);
        String toolVersion = requiredString(body.get("toolVersion"), "toolVersion", 32,
                requestId, null);
        Map<String, Object> input = object(body.get("input"), "input", requestId, null);
        if (!body.containsKey("dryRun") || !(body.get("dryRun") instanceof Boolean dryRun)) {
            throw invalidInput("dryRun 必须显式为 false", requestId, null);
        }
        if (dryRun) {
            throw invalidInput("首批只读 Tool 不支持 dryRun", requestId, null);
        }
        if (body.get("idempotencyKey") != null || body.get("approvalToken") != null) {
            throw invalidInput("首批只读 Tool 不接受 idempotencyKey 或 approvalToken", requestId, null);
        }
        ClientContext context = clientContext(body.get("clientContext"), requestId);
        return new ExecutionCall(requestId, taskId, stepId, toolId, toolVersion,
                input, context.source(), context.intentSummary());
    }

    private static ClientContext clientContext(Object value, String requestId) {
        Map<String, Object> context = object(value, "clientContext", requestId, null);
        rejectExtraFields(context, CLIENT_CONTEXT_FIELDS, requestId, null, "clientContext");
        optionalString(context.get("locale"), "locale", 32, requestId, null);
        String source = requiredString(context.get("source"), "source", 32, requestId, null);
        if (!CLIENT_SOURCES.contains(source)) {
            throw invalidInput("clientContext.source 不受支持", requestId, null);
        }
        String intent = optionalString(context.get("intentSummary"), "intentSummary", 512,
                requestId, null);
        return new ClientContext(source, intent);
    }

    private void insertAudit(String auditRef, String executionId, ExecutionCall call,
                             ApiPrincipal principal, ToolCatalogService.ToolSpec spec,
                             Instant startedAt, Instant completedAt, String resultStatus,
                             String errorCode) {
        ToolExecutionAudit audit = new ToolExecutionAudit();
        audit.setAuditRef(auditRef);
        audit.setExecutionId(executionId);
        audit.setRequestId(call.requestId());
        audit.setTaskId(call.taskId());
        audit.setStepId(call.stepId());
        audit.setSubjectId(principal.subjectId());
        audit.setCredentialId(principal.credentialId());
        audit.setSource(call.source());
        audit.setServerId(serverIdentity.serverId());
        audit.setToolId(call.toolId());
        audit.setToolVersion(call.toolVersion());
        audit.setRequiredScopes(spec.requiredScope());
        audit.setAuthorizationResult("allow");
        audit.setPolicyVersion(principal.permissionVersion());
        audit.setParameterSummary(parameterSummary(call));
        audit.setResultStatus(resultStatus);
        audit.setErrorCode(errorCode);
        audit.setIntentSummary(call.intentSummary());
        audit.setStartedAt(startedAt.toString());
        audit.setCompletedAt(completedAt.toString());
        auditRepository.insert(audit);
    }

    private static String parameterSummary(ExecutionCall call) {
        if (ToolCatalogService.HEALTH_TOOL.equals(call.toolId())) {
            return "none";
        }
        if (ToolCatalogService.ONLINE_TOOL.equals(call.toolId())) {
            Object pageSize = call.input().getOrDefault("pageSize", 100);
            return "pageSize=" + pageSize + ",cursorPresent=" + (call.input().get("cursor") != null);
        }
        if (ToolCatalogService.AGENT_INVESTIGATE_TOOL.equals(call.toolId())) {
            String message = String.valueOf(call.input().getOrDefault("message", ""));
            return "messageHash=" + shortHash(message) + ",messageLength=" + message.length()
                    + ",conversationIdPresent=" + (call.input().get("conversationId") != null);
        }
        if (ToolCatalogService.AGENT_CLOSE_TOOL.equals(call.toolId())) {
            return "conversationIdHash=" + shortHash(String.valueOf(call.input().get("conversationId")));
        }
        if (ToolCatalogService.INVENTORY_TOOL.equals(call.toolId())) {
            return "characterId=" + call.input().get("characterId");
        }
        return "none";
    }

    private Map<String, Object> investigateWithAgent(ApiPrincipal principal, ExecutionCall call) {
        rejectExtraFields(call.input(), Set.of("conversationId", "message"),
                call.requestId(), null, "server.agent.investigate input");
        String message = requiredString(call.input().get("message"), "message", 2000,
                call.requestId(), null).trim();
        String conversationId = optionalString(call.input().get("conversationId"),
                "conversationId", 64, call.requestId(), null);
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "twish-" + UUID.randomUUID().toString().replace("-", "");
        }
        if (!conversationId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw invalidInput("conversationId 格式无效", call.requestId(), null);
        }
        ServerAgentService.InvestigationResult reply = serverAgent.investigate(
                new ServerAgentService.InvestigationRequest(conversationId, message,
                        call.requestId(), principal.subjectId(), principal.credentialId(),
                        "twish-" + call.source()));
        return Map.of(
                "conversationId", reply.conversationId(),
                "reply", reply.reply(),
                "model", reply.model(),
                "executedTools", reply.executedTools(),
                "auditRefs", reply.auditRefs(),
                "inputTokens", reply.inputTokens(),
                "outputTokens", reply.outputTokens());
    }

    private Map<String, Object> closeAgentConversation(ApiPrincipal principal, ExecutionCall call) {
        rejectExtraFields(call.input(), Set.of("conversationId"), call.requestId(), null,
                "server.agent.conversation.close input");
        String conversationId = requiredString(call.input().get("conversationId"),
                "conversationId", 64, call.requestId(), null);
        if (!conversationId.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw invalidInput("conversationId 格式无效", call.requestId(), null);
        }
        return Map.of("conversationId", conversationId,
                "evicted", serverAgent.closeConversation(conversationId, principal.subjectId()));
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private static Map<String, Object> resultEnvelope(String requestId, String executionId,
                                                       Map<String, Object> output, String auditRef,
                                                       Instant completedAt) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", ApiContract.VERSION);
        result.put("executionId", executionId);
        result.put("requestId", requestId);
        result.put("status", "succeeded");
        result.put("output", output);
        result.put("content", List.of());
        result.put("artifacts", List.of());
        result.put("auditRef", auditRef);
        result.put("completedAt", completedAt.toString());
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String field, String requestId,
                                              String executionId) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw invalidInput(field + " 必须是对象", requestId, executionId);
        }
        for (Object key : raw.keySet()) {
            if (!(key instanceof String)) {
                throw invalidInput(field + " 的字段名必须是字符串", requestId, executionId);
            }
        }
        return (Map<String, Object>) raw;
    }

    private static void rejectExtraFields(Map<String, Object> value, Set<String> allowed,
                                          String requestId, String executionId, String label) {
        for (String field : value.keySet()) {
            if (!allowed.contains(field)) {
                throw invalidInput(label + " 包含未知字段: " + field, requestId, executionId);
            }
        }
    }

    private static String requiredString(Object value, String field, int maxLength,
                                         String requestId, String executionId) {
        String result = optionalString(value, field, maxLength, requestId, executionId);
        if (result == null || result.isBlank()) {
            throw invalidInput(field + " 不能为空", requestId, executionId);
        }
        return result;
    }

    private static String optionalString(Object value, String field, int maxLength,
                                         String requestId, String executionId) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.length() > maxLength) {
            throw invalidInput(field + " 格式无效", requestId, executionId);
        }
        return text;
    }

    private static ToolProtocolException invalidInput(String message, String requestId,
                                                      String executionId) {
        return new ToolProtocolException(io.micronaut.http.HttpStatus.BAD_REQUEST,
                "invalid_input", message, false, executionId, requestId, Map.of());
    }

    private static String publicId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void pruneExpiredExecutions() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(10));
        byExecutionId.entrySet().removeIf(entry -> entry.getValue().completedAt().isBefore(cutoff));
        executionIdByDedupeKey.entrySet().removeIf(entry -> !byExecutionId.containsKey(entry.getValue()));
    }

    public record ExecutionResult(String requestId, Map<String, Object> result) {
    }

    private record ExecutionCall(String requestId, String taskId, String stepId, String toolId,
                                 String toolVersion, Map<String, Object> input, String source,
                                 String intentSummary) {
    }

    private record ClientContext(String source, String intentSummary) {
    }

    private record StoredExecution(String subjectId, String requiredScope,
                                   Map<String, Object> result, Instant completedAt) {
    }
}
