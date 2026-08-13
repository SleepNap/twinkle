package org.gms.service.agent;

import org.gms.i18n.I18n;

/** AI 默认关闭或当前拓扑不承载 AI 时使用的管理能力面空实现。 */
public final class UnavailableServerAgentService implements ServerAgentService {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public InvestigationResult investigate(InvestigationRequest request) {
        throw new IllegalStateException(I18n.message("error.agent.not_enabled"));
    }

    @Override
    public boolean closeConversation(String conversationId, String subjectId) {
        return false;
    }
}
