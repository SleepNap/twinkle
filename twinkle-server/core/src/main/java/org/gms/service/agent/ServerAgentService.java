package org.gms.service.agent;

import java.util.List;

/** 管理能力面使用的服务端 Agent 稳定契约；HTTP 层不依赖具体 AI 模块。 */
public interface ServerAgentService {

    /** 当前进程是否已装配可用模型。 */
    public boolean available();

    /** 执行一次带 Subject 隔离的只读调查。 */
    public InvestigationResult investigate(InvestigationRequest request);

    /** 释放指定 Subject 的会话记忆。 */
    public boolean closeConversation(String conversationId, String subjectId);

    /** 能力面传入的可信身份上下文和不可信用户消息。 */
    public record InvestigationRequest(String conversationId, String message, String requestId,
                                       String subjectId, String credentialId, String source) {
    }

    /** 可序列化的调查结果，不包含模型隐藏推理。 */
    public record InvestigationResult(String conversationId, String reply, String model,
                                      List<String> executedTools, List<String> auditRefs,
                                      int inputTokens, int outputTokens) {
        public InvestigationResult {
            executedTools = executedTools == null ? List.of() : List.copyOf(executedTools);
            auditRefs = auditRefs == null ? List.of() : List.copyOf(auditRefs);
        }
    }
}
