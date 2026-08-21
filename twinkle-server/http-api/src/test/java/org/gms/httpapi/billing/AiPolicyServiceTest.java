package org.gms.httpapi.billing;

import org.gms.config.ConfigFacade;
import org.gms.data.entity.AiUsagePolicy;
import org.gms.data.repo.AiUsagePolicyRepository;
import org.gms.service.agent.AiGovernanceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 权限与预算策略判定测试。
 *
 * <p>锁住四件容易改错的事：全局层对所有调用方生效、无策略行放行、限额 {@code 0=不限制}、
 * 日窗口过期后计数器重置。
 */
class AiPolicyServiceTest {

    private static final String MODEL = "deepseek/deepseek-chat";

    @Test
    void 全局关闭时拒绝且属于服务不可用类() {
        AiPolicyService service = service(new MemoryPolicies(),
                Map.of("ai.runtime.enabled", "false"));

        assertThatThrownBy(() -> service.checkGlobal(MODEL))
                .isInstanceOf(AiGovernanceException.class)
                .hasFieldOrPropertyWithValue("code", "ai_disabled")
                .extracting(e -> ((AiGovernanceException) e).kind())
                .isEqualTo(AiGovernanceException.Kind.UNAVAILABLE);
    }

    @Test
    void 全局白名单不含当前模型时按策略类拒绝() {
        AiPolicyService service = service(new MemoryPolicies(),
                Map.of("ai.allowed.models", "local-rule/deterministic"));

        assertThatThrownBy(() -> service.checkGlobal(MODEL))
                .isInstanceOf(AiGovernanceException.class)
                .hasFieldOrPropertyWithValue("code", "model_not_allowed")
                .extracting(e -> ((AiGovernanceException) e).kind())
                .isEqualTo(AiGovernanceException.Kind.POLICY);
    }

    @Test
    void 全局白名单留空表示不限制() {
        AiPolicyService service = service(new MemoryPolicies(), Map.of("ai.allowed.models", ""));

        assertThatCode(() -> service.checkGlobal(MODEL)).doesNotThrowAnyException();
    }

    @Test
    void 账号无策略行时放行() {
        AiPolicyService service = service(new MemoryPolicies(), Map.of());

        assertThatCode(() -> service.checkAccount(1L, MODEL)).doesNotThrowAnyException();
    }

    @Test
    void 账号被禁用时拒绝() {
        MemoryPolicies policies = new MemoryPolicies();
        policies.save(policy(1L, 0, "", 0L, 0L, 0L, Instant.now().toString()));
        AiPolicyService service = service(policies, Map.of());

        assertThatThrownBy(() -> service.checkAccount(1L, MODEL))
                .isInstanceOf(AiGovernanceException.class)
                .hasFieldOrPropertyWithValue("code", "policy_disabled");
    }

    @Test
    void 日调用上限达到时拒绝() {
        MemoryPolicies policies = new MemoryPolicies();
        AiUsagePolicy policy = policy(1L, 1, "", 0L, 5L, 0L, Instant.now().toString());
        policy.setDailyCallUsed(5L);
        policies.save(policy);
        AiPolicyService service = service(policies, Map.of());

        assertThatThrownBy(() -> service.checkAccount(1L, MODEL))
                .isInstanceOf(AiGovernanceException.class)
                .hasFieldOrPropertyWithValue("code", "daily_limit_exceeded");
    }

    @Test
    void 限额为0表示不限制() {
        MemoryPolicies policies = new MemoryPolicies();
        AiUsagePolicy policy = policy(1L, 1, "", 0L, 0L, 0L, Instant.now().toString());
        policy.setDailyCallUsed(9_999L);
        policy.setDailyPointUsed(9_999L);
        policies.save(policy);
        AiPolicyService service = service(policies, Map.of());

        assertThatCode(() -> service.checkAccount(1L, MODEL)).doesNotThrowAnyException();
    }

    @Test
    void 日窗口过期后重置计数器并放行() {
        MemoryPolicies policies = new MemoryPolicies();
        String expired = Instant.now().minus(25, ChronoUnit.HOURS).toString();
        AiUsagePolicy policy = policy(1L, 1, "", 0L, 5L, 0L, expired);
        policy.setDailyCallUsed(5L);
        policies.save(policy);
        AiPolicyService service = service(policies, Map.of());

        // 窗口已过 24h：先重置再判定，不应命中上限。
        assertThatCode(() -> service.checkAccount(1L, MODEL)).doesNotThrowAnyException();
        assertThat(policies.stored.get(1L).getDailyCallUsed()).isZero();
    }

    @Test
    void 账号白名单只允许指定模型() {
        MemoryPolicies policies = new MemoryPolicies();
        policies.save(policy(1L, 1, "local-rule/deterministic", 0L, 0L, 0L, Instant.now().toString()));
        AiPolicyService service = service(policies, Map.of());

        assertThatThrownBy(() -> service.checkAccount(1L, MODEL))
                .isInstanceOf(AiGovernanceException.class)
                .hasFieldOrPropertyWithValue("code", "model_not_allowed");
        assertThatCode(() -> service.checkAccount(1L, "local-rule/deterministic"))
                .doesNotThrowAnyException();
    }

    @Test
    void 记录用量走原子自增() {
        MemoryPolicies policies = new MemoryPolicies();
        policies.save(policy(1L, 1, "", 0L, 0L, 0L, Instant.now().toString()));
        AiPolicyService service = service(policies, Map.of());

        service.recordUsage(1L, 7L, 120L);

        AiUsagePolicy stored = policies.stored.get(1L);
        assertThat(stored.getDailyPointUsed()).isEqualTo(7L);
        assertThat(stored.getDailyCallUsed()).isEqualTo(1L);
        assertThat(stored.getDailyTokenUsed()).isEqualTo(120L);
    }

    private static AiPolicyService service(AiUsagePolicyRepository policies, Map<String, String> config) {
        return new AiPolicyService(policies, new MapConfig(config));
    }

    private static AiUsagePolicy policy(Long accountId, int enabled, String allowedModels,
                                        long pointLimit, long callLimit, long tokenLimit,
                                        String windowStart) {
        AiUsagePolicy policy = new AiUsagePolicy();
        policy.setAccountId(accountId);
        policy.setEnabled(enabled);
        policy.setAllowedModels(allowedModels);
        policy.setDailyPointLimit(pointLimit);
        policy.setDailyCallLimit(callLimit);
        policy.setDailyTokenLimit(tokenLimit);
        policy.setDailyPointUsed(0L);
        policy.setDailyCallUsed(0L);
        policy.setDailyTokenUsed(0L);
        policy.setWindowStart(windowStart);
        return policy;
    }

    /** 内存策略仓库；原子方法用直接改写模拟。 */
    private static final class MemoryPolicies implements AiUsagePolicyRepository {

        private final Map<Long, AiUsagePolicy> stored = new HashMap<>();

        private void save(AiUsagePolicy policy) {
            stored.put(policy.getAccountId(), policy);
        }

        @Override
        public Optional<AiUsagePolicy> findByAccountId(Long accountId) {
            return Optional.ofNullable(stored.get(accountId));
        }

        @Override
        public List<AiUsagePolicy> findAll() {
            return new ArrayList<>(stored.values());
        }

        @Override
        public void insert(AiUsagePolicy policy) {
            save(policy);
        }

        @Override
        public void update(AiUsagePolicy policy) {
            save(policy);
        }

        @Override
        public int addDailyUsage(Long accountId, long points, long calls, long tokens, String updatedAt) {
            AiUsagePolicy policy = stored.get(accountId);
            if (policy == null) {
                return 0;
            }
            policy.setDailyPointUsed(policy.getDailyPointUsed() + points);
            policy.setDailyCallUsed(policy.getDailyCallUsed() + calls);
            policy.setDailyTokenUsed(policy.getDailyTokenUsed() + tokens);
            policy.setUpdatedAt(updatedAt);
            return 1;
        }

        @Override
        public int resetDailyWindow(Long accountId, String oldWindowStart, String newWindowStart) {
            AiUsagePolicy policy = stored.get(accountId);
            if (policy == null) {
                return 0;
            }
            String current = policy.getWindowStart();
            boolean matches = current == null ? oldWindowStart == null : current.equals(oldWindowStart);
            if (!matches) {
                return 0;
            }
            policy.setDailyPointUsed(0L);
            policy.setDailyCallUsed(0L);
            policy.setDailyTokenUsed(0L);
            policy.setWindowStart(newWindowStart);
            return 1;
        }
    }

    /** 只读内存配置门面。 */
    private static final class MapConfig implements ConfigFacade {

        private final Map<String, String> values;

        private MapConfig(Map<String, String> values) {
            this.values = values;
        }

        @Override
        public long currentVersion() {
            return 1L;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String key, Class<T> type) {
            String raw = values.get(key);
            if (raw == null) {
                return Optional.empty();
            }
            if (type == Boolean.class) {
                return Optional.of((T) Boolean.valueOf(raw));
            }
            return Optional.of((T) raw);
        }

        @Override
        public void signalChange() {
            // 测试桩不广播。
        }
    }
}
