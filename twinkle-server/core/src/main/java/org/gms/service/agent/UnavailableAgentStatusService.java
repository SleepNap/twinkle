package org.gms.service.agent;

/** 未装配 AI 模块时的运行态兜底：一律报告不可用，管理面照常可查询。 */
public final class UnavailableAgentStatusService implements AgentStatusService {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String modelDescriptor() {
        return "";
    }

    @Override
    public boolean externalModel() {
        return false;
    }

    @Override
    public long callCount() {
        return 0L;
    }

    @Override
    public int consecutiveFailures() {
        return 0;
    }

    @Override
    public String lastError() {
        return "";
    }

    @Override
    public String lastErrorAt() {
        return "";
    }
}
