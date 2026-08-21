package org.gms.httpapi.billing;

import lombok.extern.log4j.Log4j2;
import org.gms.config.ConfigFacade;
import org.gms.data.entity.AiUsagePolicy;
import org.gms.data.repo.AiUsagePolicyRepository;
import org.gms.i18n.I18n;
import org.gms.service.agent.AiGovernanceException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * AI 权限与预算策略判定（全局层 + 账号层）。
 *
 * <p>与 {@link BillingService} 分工：本类判"能不能用"（开关、模型白名单、日用量上限），
 * BillingService 判"付不付得起"（积分余额、订阅计划三档限额）。
 *
 * <p><b>全局策略对管理员凭据同样生效</b>。管理员 key（scope {@code *}）免计费，
 * 但若不受全局开关与白名单约束，一旦泄漏就等于无限免费调用外部模型。故判定顺序上
 * 全局层必须先于"解析计费账号"执行——账号层才是免计费凭据跳过的部分。
 *
 * <p>账号没有策略行 = 不限制（只受全局层约束），保证新表上线不锁死存量账号。
 */
@Log4j2
public final class AiPolicyService {

    /** 运行期 AI 总开关配置键；区别于装配期的 {@code twinkle.ai.enabled}。 */
    private static final String RUNTIME_ENABLED_KEY = "ai.runtime.enabled";
    /** 全局模型白名单配置键（逗号分隔 descriptor，留空=不限）。 */
    private static final String ALLOWED_MODELS_KEY = "ai.allowed.models";
    /** 日用量窗口长度（小时）。 */
    private static final int DAILY_WINDOW_HOURS = 24;

    private final AiUsagePolicyRepository policyRepository;
    private final ConfigFacade configFacade;

    public AiPolicyService(AiUsagePolicyRepository policyRepository, ConfigFacade configFacade) {
        this.policyRepository = policyRepository;
        this.configFacade = configFacade;
    }

    /**
     * 全局层判定：运行期总开关 + 全局模型白名单。所有调用方（含管理员凭据）都要过。
     *
     * @throws AiGovernanceException 全局关闭或模型不在白名单
     */
    public void checkGlobal(String modelDescriptor) {
        if (!configFacade.getOrDefault(RUNTIME_ENABLED_KEY, true)) {
            throw new AiGovernanceException("ai_disabled",
                    I18n.message("error.ai.policy.runtime_disabled"),
                    AiGovernanceException.Kind.UNAVAILABLE);
        }
        List<String> allowed = parseModels(configFacade.getOrDefault(ALLOWED_MODELS_KEY, ""));
        if (!allowed.isEmpty() && !allowed.contains(modelDescriptor)) {
            throw new AiGovernanceException("model_not_allowed",
                    I18n.message("error.ai.policy.model_not_allowed", modelDescriptor),
                    AiGovernanceException.Kind.POLICY);
        }
    }

    /**
     * 账号层判定：策略开关 + 账号模型白名单 + 日调用/积分上限。
     *
     * <p>无策略行直接放行。判定前先滚动日窗口。
     *
     * @throws AiGovernanceException 账号被禁用、模型不允许或已达日上限
     */
    public void checkAccount(Long accountId, String modelDescriptor) {
        Optional<AiUsagePolicy> found = policyRepository.findByAccountId(accountId);
        if (found.isEmpty()) {
            return;
        }
        AiUsagePolicy policy = rollDailyWindow(found.get(), Instant.now());
        if (policy.getEnabled() != null && policy.getEnabled() == 0) {
            throw new AiGovernanceException("policy_disabled",
                    I18n.message("error.ai.policy.account_disabled"),
                    AiGovernanceException.Kind.POLICY);
        }
        List<String> allowed = parseModels(policy.getAllowedModels());
        if (!allowed.isEmpty() && !allowed.contains(modelDescriptor)) {
            throw new AiGovernanceException("model_not_allowed",
                    I18n.message("error.ai.policy.model_not_allowed", modelDescriptor),
                    AiGovernanceException.Kind.POLICY);
        }
        if (exceeds(policy.getDailyCallUsed(), policy.getDailyCallLimit())
                || exceeds(policy.getDailyPointUsed(), policy.getDailyPointLimit())
                || exceeds(policy.getDailyTokenUsed(), policy.getDailyTokenLimit())) {
            throw new AiGovernanceException("daily_limit_exceeded",
                    I18n.message("error.ai.policy.daily_limit_exceeded"));
        }
    }

    /**
     * 累加一次调用的日用量（原子自增，无策略行时是空操作）。
     *
     * <p>调用后结算，失败只记日志：模型结果已产出，计数失败不应连带失败整个请求。
     */
    public void recordUsage(Long accountId, long points, long tokens) {
        try {
            policyRepository.addDailyUsage(accountId, points, 1L, tokens, Instant.now().toString());
        } catch (RuntimeException e) {
            log.warn(I18n.message("log.ai.policy.usage_count_failed"), accountId, e);
        }
    }

    /** 读取账号策略；无行时返回空。 */
    public Optional<AiUsagePolicy> find(Long accountId) {
        return policyRepository.findByAccountId(accountId);
    }

    /** 列出全部策略。 */
    public List<AiUsagePolicy> findAll() {
        return policyRepository.findAll();
    }

    /** 全局层配置快照（管理面展示）。 */
    public GlobalPolicy globalPolicy() {
        return new GlobalPolicy(configFacade.getOrDefault(RUNTIME_ENABLED_KEY, true),
                parseModels(configFacade.getOrDefault(ALLOWED_MODELS_KEY, "")));
    }

    /** 新增或更新账号策略；返回落库后的策略。 */
    public AiUsagePolicy upsert(Long accountId, Integer enabled, String allowedModels,
                                Long dailyPointLimit, Long dailyCallLimit, Long dailyTokenLimit,
                                String updatedBy) {
        String now = Instant.now().toString();
        Optional<AiUsagePolicy> found = policyRepository.findByAccountId(accountId);
        AiUsagePolicy policy = found.orElseGet(AiUsagePolicy::new);
        policy.setAccountId(accountId);
        policy.setEnabled(enabled == null ? 1 : enabled);
        policy.setAllowedModels(allowedModels == null ? "" : allowedModels.trim());
        policy.setDailyPointLimit(nonNegative(dailyPointLimit));
        policy.setDailyCallLimit(nonNegative(dailyCallLimit));
        policy.setDailyTokenLimit(nonNegative(dailyTokenLimit));
        policy.setUpdatedAt(now);
        policy.setUpdatedBy(updatedBy);
        if (found.isEmpty()) {
            policy.setDailyPointUsed(0L);
            policy.setDailyCallUsed(0L);
            policy.setDailyTokenUsed(0L);
            policy.setWindowStart(now);
            policy.setCreatedAt(now);
            policyRepository.insert(policy);
        } else {
            policyRepository.update(policy);
        }
        return policy;
    }

    /**
     * 日窗口滚动：越过 24h 则条件重置计数器。
     *
     * <p>用条件更新（仅当窗口起点仍是读到的旧值）而非读-改-写，避免并发下重复清零丢用量。
     * 重置成功后回读，保证后续判定看到的是重置后的计数。
     */
    private AiUsagePolicy rollDailyWindow(AiUsagePolicy policy, Instant now) {
        String windowStart = policy.getWindowStart();
        if (windowStart != null && !isExpired(windowStart, now)) {
            return policy;
        }
        int updated = policyRepository.resetDailyWindow(policy.getAccountId(), windowStart, now.toString());
        if (updated == 0) {
            // 已被其它线程重置；回读最新值而不是沿用手里的旧快照。
            return policyRepository.findByAccountId(policy.getAccountId()).orElse(policy);
        }
        policy.setDailyPointUsed(0L);
        policy.setDailyCallUsed(0L);
        policy.setDailyTokenUsed(0L);
        policy.setWindowStart(now.toString());
        return policy;
    }

    private static boolean isExpired(String windowStart, Instant now) {
        try {
            return now.isAfter(Instant.parse(windowStart).plus(DAILY_WINDOW_HOURS, ChronoUnit.HOURS));
        } catch (RuntimeException e) {
            // 窗口起点不可解析（脏数据）：当作已过期重置，避免永久卡在旧窗口。
            return true;
        }
    }

    /** 限额语义与订阅计划一致：{@code >0} 生效，{@code 0} 或 null 表示不限制。 */
    private static boolean exceeds(Long used, Long limit) {
        return limit != null && limit > 0 && used != null && used >= limit;
    }

    private static Long nonNegative(Long value) {
        return value == null || value < 0 ? 0L : value;
    }

    private static List<String> parseModels(String raw) {
        List<String> models = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return models;
        }
        for (String model : raw.split(",")) {
            String trimmed = model.trim();
            if (!trimmed.isEmpty()) {
                models.add(trimmed);
            }
        }
        return models;
    }

    /** 全局策略快照。 */
    public record GlobalPolicy(boolean runtimeEnabled, List<String> allowedModels) {

        public GlobalPolicy {
            allowedModels = allowedModels == null ? List.of() : List.copyOf(allowedModels);
        }
    }
}
