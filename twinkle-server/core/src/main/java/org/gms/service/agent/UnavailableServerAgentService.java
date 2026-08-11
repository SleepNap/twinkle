package org.gms.service.agent;

/** AI 默认关闭或当前拓扑不承载 AI 时使用的管理能力面空实现。 */
public final class UnavailableServerAgentService implements ServerAgentService {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public InvestigationResult investigate(InvestigationRequest request) {
        throw new IllegalStateException("服务端 Agent 当前未启用");
    }

    @Override
    public boolean closeConversation(String conversationId, String subjectId) {
        return false;
    }
}
