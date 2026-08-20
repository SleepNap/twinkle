package org.gms.httpapi.billing;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.ApiKeyRecord;
import org.gms.data.repo.ApiKeyRepository;
import org.gms.i18n.I18n;
import org.gms.service.agent.AiGovernanceException;
import org.gms.service.agent.AiGovernanceService;

import java.util.List;
import java.util.Optional;

/**
 * AI 治理实现（core {@link AiGovernanceService} 契约的真实实现）：策略准入 + 积分计费。
 *
 * <p>由 AI 门面在调查入口内部调用，使能力面、AI HTTP 接口与游戏内值班 GM 三条入口
 * 共用同一个治理点，避免每条入口各自接线导致漏计费（此前 {@code /api/v1/ai/chat}
 * 就因为绕过能力面而完全不扣费）。
 *
 * <p>只拿得到 subjectId / credentialId，故按 credentialId 反查 API-key 记录还原
 * scope 与计费账号，而不是依赖调用方传入的投影。
 *
 * <p><b>判定顺序是有意为之</b>：全局策略（{@link AiPolicyService#checkGlobal}）先于
 * 计费账号解析。管理员凭据免计费会在解析处短路返回，若全局判定放在其后，
 * 管理员 key 将不受总开关与模型白名单约束——一旦泄漏即等于无限免费调用外部模型。
 */
@Log4j2
public final class BillingAiGovernance implements AiGovernanceService {

    private static final String ADMIN_SCOPE = "*";
    private static final String WEB_SEARCH_TOOL = "web_search";

    private final BillingService billingService;
    private final AiPolicyService policyService;
    private final ApiKeyRepository apiKeyRepository;

    public BillingAiGovernance(BillingService billingService, AiPolicyService policyService,
                               ApiKeyRepository apiKeyRepository) {
        this.billingService = billingService;
        this.policyService = policyService;
        this.apiKeyRepository = apiKeyRepository;
    }

    @Override
    public GovernanceTicket precheck(String subjectId, String credentialId, String modelDescriptor) {
        // 1) 全局层：总开关 + 全局模型白名单，对管理员凭据同样生效。
        policyService.checkGlobal(modelDescriptor);

        // 2) 解析计费账号；管理员与内部凭据到此为止（免计费，也不受账号级预算约束）。
        Long accountId = resolveBillingAccount(credentialId);
        if (accountId == null) {
            return GovernanceTicket.free();
        }

        // 3) 账号层：策略开关 + 账号模型白名单 + 日用量上限。
        policyService.checkAccount(accountId, modelDescriptor);

        // 4) 余额与订阅计划限额。
        try {
            billingService.precheck(accountId);
        } catch (BillingException e) {
            throw new AiGovernanceException(e.code(), e.getMessage());
        }
        return new GovernanceTicket(accountId, true);
    }

    @Override
    public long settle(GovernanceTicket ticket, String model, int inputTokens,
                       int outputTokens, List<String> executedTools) {
        if (ticket == null || !ticket.billable() || ticket.accountId() == null) {
            return 0L;
        }
        long points = 0L;
        try {
            points = billingService.charge(ticket.accountId(), model, inputTokens, outputTokens,
                    webSearchCount(executedTools));
        } catch (RuntimeException e) {
            // 模型结果已产出，扣费失败不连带失败整个请求（沿用既有非事务性计费语义）。
            log.warn(I18n.message("log.billing.charge_failed"), ticket.accountId(), model, e);
        }
        // 即便扣费失败也要计入日用量：调用确实发生了，不记会让日预算被绕过。
        policyService.recordUsage(ticket.accountId(), points, (long) inputTokens + outputTokens);
        return points;
    }

    /**
     * 还原计费账号：管理员凭据（scope 含 {@code *}）与查不到记录的内部 / bootstrap 凭据免计费；
     * 普通 key 必须绑定账号，否则拒绝调用。
     */
    private Long resolveBillingAccount(String credentialId) {
        if (credentialId == null || credentialId.isBlank()) {
            return null;
        }
        Optional<ApiKeyRecord> found = apiKeyRepository.findByCredentialId(credentialId);
        if (found.isEmpty()) {
            return null;
        }
        ApiKeyRecord record = found.get();
        if (hasAdminScope(record.getScopes())) {
            return null;
        }
        if (record.getOwnerAccountId() == null) {
            throw new AiGovernanceException("billing_account_required",
                    I18n.message("error.billing.api_key_unlinked"));
        }
        return record.getOwnerAccountId();
    }

    private static boolean hasAdminScope(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return false;
        }
        for (String scope : scopes.split(",")) {
            if (ADMIN_SCOPE.equals(scope.trim())) {
                return true;
            }
        }
        return false;
    }

    private static int webSearchCount(List<String> executedTools) {
        if (executedTools == null) {
            return 0;
        }
        return (int) executedTools.stream().filter(WEB_SEARCH_TOOL::equals).count();
    }
}
