package org.gms.service.agent;

import java.util.List;

/**
 * AI 调用的准入与计费治理稳定契约。
 *
 * <p>计费实现依赖 API-key 与积分账户（在 http-api 模块），而 AI 模块不得依赖 http-api，
 * 故把治理下沉为 core 契约：AI 模块经本接口完成计费，编译期不产生反向依赖。
 * 同 {@link ServerAgentService} / AdminService 的既有范式。
 *
 * <p><b>唯一计费点</b>：实现挂在 AI 门面的调查入口内部，使能力面、AI HTTP 接口、
 * 游戏内值班 GM 三条入口自动全覆盖，新增入口无需重复接线。
 */
public interface AiGovernanceService {

    /**
     * 调用模型前的准入与额度预检。
     *
     * @throws AiGovernanceException 额度不足或调用方不具备计费主体时抛出
     */
    public GovernanceTicket precheck(String subjectId, String credentialId);

    /**
     * 调用模型后结算扣费。
     *
     * <p>实现内部吞掉异常并记录日志：模型结果已经产出，扣费失败不应连带失败整个请求
     * （沿用既有计费语义，非事务性扣费）。
     */
    public void settle(GovernanceTicket ticket, String model, int inputTokens,
                       int outputTokens, List<String> executedTools);

    /** 结算凭据；{@code billable=false} 表示内部调用或管理员凭据，免计费。 */
    public record GovernanceTicket(Long accountId, boolean billable) {

        /** 免计费凭据。 */
        public static GovernanceTicket free() {
            return new GovernanceTicket(null, false);
        }
    }
}
