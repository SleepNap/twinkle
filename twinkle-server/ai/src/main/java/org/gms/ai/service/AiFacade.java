package org.gms.ai.service;

import lombok.extern.log4j.Log4j2;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import org.gms.ai.model.AiModelBundle;
import org.gms.ai.model.tool.AgentToolAudit;
import org.gms.data.entity.AiUsageEntity;
import org.gms.data.repo.AiUsageRepository;
import org.gms.i18n.I18n;
import org.gms.service.agent.ServerAgentService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 服务门面（架构 M3-2：AI 请求编排 + 计费/观测落 SQLite）。
 *
 * <p>包装 {@link AiAssistant}（AiServices 声明式 Agent），每次对话记录
 * 工具名/请求/响应长度/耗时到 {@code ai_usage} 表（计费复用 Dao 设计）。
 * 暴露调用次数计数（观测）。
 *
 * <p>本类不加 @Singleton——由 bootstrap 装配。
 */
@Log4j2
public final class AiFacade implements ServerAgentService {



    private final AiAssistant assistant;
    private final AiUsageRepository usageRepository;
    private final AiModelBundle model;
    private final int maxConversations;
    private final AtomicLong callCount = new AtomicLong();
    private final Object[] conversationLocks = new Object[64];
    private final Map<String, Long> conversationAccess = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> activeConversations = new ConcurrentHashMap<>();

    public AiFacade(AiAssistant assistant, AiUsageRepository usageRepository,
                    AiModelBundle model, int maxConversations) {
        this.assistant = assistant;
        this.usageRepository = usageRepository;
        this.model = model;
        this.maxConversations = Math.max(1, maxConversations);
        for (int i = 0; i < conversationLocks.length; i++) {
            conversationLocks[i] = new Object();
        }
    }

    /** 带会话记忆的只读值班 GM 调查；同一会话串行，防 LangChain4j ChatMemory 并发损坏。 */
    public AgentReply investigate(String conversationId, String message, String requestId,
                                  String subjectId, String credentialId, String source) {
        String safeConversationId = validatedConversationId(conversationId);
        String safeMessage = validatedMessage(message);
        String safeSubjectId = blankDefault(subjectId, "server-agent");
        String memoryId = conversationMemoryId(safeConversationId, safeSubjectId);
        long start = System.nanoTime();
        AtomicInteger active = activeConversations.computeIfAbsent(memoryId, ignored -> new AtomicInteger());
        active.incrementAndGet();
        try {
            synchronized (lockFor(memoryId)) {
                InvocationParameters parameters = InvocationParameters.from(Map.of(
                        "conversationId", safeConversationId,
                        "requestId", blankDefault(requestId, UUID.randomUUID().toString()),
                        "subjectId", safeSubjectId,
                        "credentialId", blankDefault(credentialId, "internal-agent"),
                        "source", blankDefault(source, "server-agent")));
                Result<String> result = assistant.investigate(memoryId, safeMessage, parameters);
                String reply = result.content();
                List<String> auditRefs = auditRefs(parameters);
                List<String> executedTools = result.toolExecutions() == null ? List.of()
                        : result.toolExecutions().stream().map(execution -> execution.request().name()).toList();
                TokenUsage usage = result.tokenUsage();
                AgentReply agentReply = new AgentReply(safeConversationId, reply, model.descriptor(),
                        executedTools, auditRefs, tokenCount(usage, true), tokenCount(usage, false));
                record("agent:" + model.descriptor(), safeMessage, reply, start,
                        model.descriptor(), agentReply.inputTokens(), agentReply.outputTokens());
                touchConversation(memoryId);
                return agentReply;
            }
        } catch (RuntimeException e) {
            log.error(I18n.message("log.ai.investigation_error"), safeConversationId, e);
            throw e;
        } finally {
            if (active.decrementAndGet() == 0) {
                activeConversations.remove(memoryId, active);
            }
            evictIdleConversations();
        }
    }

    /** 兼容原有内部调用：每次使用独立会话，不携带外部身份。 */
    public String chat(String message) {
        return investigate("internal-" + UUID.randomUUID(), message, UUID.randomUUID().toString(),
                "server-agent", "internal-agent", "internal").reply();
    }

    /** 流式对话：返回 TokenStream（调用方订阅增量）。 */
    public dev.langchain4j.service.TokenStream stream(String message) {
        String conversationId = "stream-" + UUID.randomUUID();
        InvocationParameters parameters = InvocationParameters.from(Map.of(
                "conversationId", conversationId,
                "requestId", UUID.randomUUID().toString(),
                "subjectId", "server-agent",
                "credentialId", "internal-agent",
                "source", "internal"));
        return assistant.stream(conversationId, validatedMessage(message), parameters);
    }

    /** 结构化报表（POJO 自动解析）。 */
    public OnlineReport onlineReport() {
        long start = System.nanoTime();
        OnlineReport report = assistant.onlineReport("在线统计");
        record("online_report", "在线统计", String.valueOf(report.getOnlineCount()), start,
                model.descriptor(), 0, 0);
        return report;
    }

    /** 累计调用次数（观测）。 */
    public long callCount() {
        return callCount.get();
    }

    public String modelDescriptor() {
        return model.descriptor();
    }

    public boolean externalModel() {
        return model.external();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public InvestigationResult investigate(InvestigationRequest request) {
        AgentReply reply = investigate(request.conversationId(), request.message(), request.requestId(),
                request.subjectId(), request.credentialId(), request.source());
        return new InvestigationResult(reply.conversationId(), reply.reply(), reply.model(),
                reply.executedTools(), reply.auditRefs(), reply.inputTokens(), reply.outputTokens());
    }

    /** 主动结束会话并释放 ChatMemory。 */
    @Override
    public boolean closeConversation(String conversationId, String subjectId) {
        String safeConversationId = validatedConversationId(conversationId);
        String memoryId = conversationMemoryId(safeConversationId, blankDefault(subjectId, "server-agent"));
        synchronized (lockFor(memoryId)) {
            synchronized (conversationAccess) {
                conversationAccess.remove(memoryId);
            }
            return assistant.evictChatMemory(memoryId);
        }
    }

    private void record(String toolName, String request, String reply, long startNanos,
                        String modelDescriptor, int inputTokens, int outputTokens) {
        callCount.incrementAndGet();
        AiUsageEntity usage = new AiUsageEntity();
        usage.setToolName(toolName.length() > 100 ? toolName.substring(0, 100) : toolName);
        // 玩家问题可能包含账号、交易和隐私信息：只存不可逆摘要与长度，不落原文。
        usage.setRequestText("sha256=" + shortHash(request) + ";length=" + request.length());
        usage.setResponseLength(reply == null ? 0 : reply.length());
        usage.setElapsedMs((int) ((System.nanoTime() - startNanos) / 1_000_000));
        usage.setModel(modelDescriptor);
        usage.setInputTokens(inputTokens);
        usage.setOutputTokens(outputTokens);
        try {
            usageRepository.insert(usage);
        } catch (RuntimeException e) {
            log.warn(I18n.message("log.ai.usage_record_failed"), e);
        }
    }

    private Object lockFor(String conversationId) {
        return conversationLocks[Math.floorMod(conversationId.hashCode(), conversationLocks.length)];
    }

    private void touchConversation(String conversationId) {
        synchronized (conversationAccess) {
            conversationAccess.remove(conversationId);
            conversationAccess.put(conversationId, System.nanoTime());
        }
    }

    private void evictIdleConversations() {
        List<String> evictions = new ArrayList<>();
        synchronized (conversationAccess) {
            while (conversationAccess.size() > maxConversations) {
                String oldest = conversationAccess.keySet().iterator().next();
                if (activeConversations.containsKey(oldest)) {
                    conversationAccess.remove(oldest);
                    conversationAccess.put(oldest, System.nanoTime());
                    break;
                }
                conversationAccess.remove(oldest);
                evictions.add(oldest);
            }
        }
        for (String conversationId : evictions) {
            synchronized (lockFor(conversationId)) {
                assistant.evictChatMemory(conversationId);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> auditRefs(InvocationParameters parameters) {
        List<String> refs = parameters.get(AgentToolAudit.AUDIT_REFS_PARAMETER);
        return refs == null ? List.of() : List.copyOf(refs);
    }

    private static int tokenCount(TokenUsage usage, boolean input) {
        if (usage == null) {
            return 0;
        }
        Integer value = input ? usage.inputTokenCount() : usage.outputTokenCount();
        return value == null ? 0 : value;
    }

    private static String validatedConversationId(String conversationId) {
        String value = blankDefault(conversationId, "conv-" + UUID.randomUUID());
        if (!value.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException(I18n.message("error.ai.conversation_id_invalid"));
        }
        return value;
    }

    private static String validatedMessage(String message) {
        if (message == null || message.isBlank() || message.length() > 2000) {
            throw new IllegalArgumentException(I18n.message("error.ai.message_length"));
        }
        return message.trim();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(I18n.message("error.crypto.algorithm_missing", "SHA-256"), e);
        }
    }

    /** 外部会话 ID 按 Subject 隔离，防不同 API 身份复用同名会话读取对方上下文。 */
    private static String conversationMemoryId(String conversationId, String subjectId) {
        return "subject:" + shortHash(subjectId) + ":" + conversationId;
    }

    /** 一次 Agent 调查的稳定响应，不包含模型隐藏推理。 */
    public record AgentReply(String conversationId, String reply, String model,
                             List<String> executedTools, List<String> auditRefs,
                             int inputTokens, int outputTokens) {
    }
}
