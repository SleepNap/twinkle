package org.gms.ai.model.tool;

import dev.langchain4j.invocation.InvocationParameters;
import org.gms.data.entity.ToolExecutionAudit;
import org.gms.data.repo.ToolExecutionAuditRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 服务端 Agent 只读工具的权威审计包装；不保存原始问题、角色名或工具结果。 */
public final class AgentToolAudit {

    public static final String AUDIT_REFS_PARAMETER = "agent.auditRefs";

    private final ToolExecutionAuditRepository repository;
    private final String serverId;

    public AgentToolAudit(ToolExecutionAuditRepository repository, String serverId) {
        this.repository = repository;
        this.serverId = serverId;
    }

    /** 执行工具并写审计；审计落库失败时 fail-closed，不把未审计证据交给模型。 */
    public String execute(String toolId, String parameterSummary, String intentSummary,
                          InvocationParameters parameters, Supplier<String> action) {
        Instant started = Instant.now();
        String auditRef = "audit_agent_" + UUID.randomUUID();
        String executionId = "agent_exec_" + UUID.randomUUID();
        String resultStatus = "succeeded";
        String errorCode = null;
        String result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            resultStatus = "failed";
            errorCode = e.getClass().getSimpleName();
            insert(auditRef, executionId, toolId, parameterSummary, intentSummary,
                    parameters, started, resultStatus, errorCode, e instanceof SecurityException);
            throw e;
        }
        insert(auditRef, executionId, toolId, parameterSummary, intentSummary,
                parameters, started, resultStatus, errorCode, false);
        auditRefs(parameters).add(auditRef);
        return result + "\nauditRef=" + auditRef;
    }

    /** 对敏感查询键生成不可逆短摘要，审计表不落角色名/账号名。 */
    public static String sensitiveKeySummary(String label, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return label + "Hash=" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private void insert(String auditRef, String executionId, String toolId, String parameterSummary,
                        String intentSummary, InvocationParameters parameters, Instant started,
                        String resultStatus, String errorCode, boolean authorizationDenied) {
        ToolExecutionAudit audit = new ToolExecutionAudit();
        audit.setAuditRef(auditRef);
        audit.setExecutionId(executionId);
        audit.setRequestId(stringParameter(parameters, "requestId", UUID.randomUUID().toString()));
        audit.setTaskId(stringParameter(parameters, "conversationId", null));
        audit.setStepId(null);
        audit.setSubjectId(stringParameter(parameters, "subjectId", "server-agent"));
        audit.setCredentialId(stringParameter(parameters, "credentialId", "internal-agent"));
        audit.setSource(stringParameter(parameters, "source", "server-agent"));
        audit.setServerId(serverId);
        audit.setToolId(toolId);
        audit.setToolVersion("1.0.0");
        boolean playerSource = "game-chat".equals(stringParameter(parameters, "source", "server-agent"));
        audit.setRequiredScopes(playerSource ? "player:self:read" : "internal:agent:read");
        audit.setAuthorizationResult(authorizationDenied ? "denied" : "allowed");
        audit.setPolicyVersion("server-agent-readonly-v1");
        audit.setParameterSummary(parameterSummary);
        audit.setResultStatus(resultStatus);
        audit.setErrorCode(errorCode);
        audit.setIntentSummary(intentSummary);
        audit.setStartedAt(started.toString());
        audit.setCompletedAt(Instant.now().toString());
        try {
            repository.insert(audit);
        } catch (RuntimeException e) {
            throw new IllegalStateException("AI 取证审计落库失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> auditRefs(InvocationParameters parameters) {
        List<String> current = parameters.get(AUDIT_REFS_PARAMETER);
        if (current != null) {
            return current;
        }
        List<String> created = java.util.Collections.synchronizedList(new ArrayList<>());
        parameters.put(AUDIT_REFS_PARAMETER, created);
        return created;
    }

    private static String stringParameter(InvocationParameters parameters, String name, String fallback) {
        Object value = parameters.get(name);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }
}
