package org.gms.service.agent;

import java.util.List;

/**
 * 无计费实现时的放行兜底（如纯频道进程、未装配 http-api 的拓扑）。
 *
 * <p>放行而非拒绝：计费是能力面的附加治理，缺失计费实现不应让 AI 功能整体不可用。
 * 真实计费由 http-api 提供实现覆盖本兜底。
 */
public final class NoopAiGovernanceService implements AiGovernanceService {

    @Override
    public GovernanceTicket precheck(String subjectId, String credentialId, String modelDescriptor) {
        return GovernanceTicket.free();
    }

    @Override
    public long settle(GovernanceTicket ticket, String model, int inputTokens,
                       int outputTokens, List<String> executedTools) {
        // 无计费实现，不结算。
        return 0L;
    }
}
