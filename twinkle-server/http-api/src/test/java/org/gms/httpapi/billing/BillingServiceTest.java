package org.gms.httpapi.billing;

import org.gms.config.ConfigFacade;
import org.gms.data.entity.ModelRate;
import org.gms.data.entity.PointAccount;
import org.gms.data.entity.PointTransaction;
import org.gms.data.entity.SubscriptionPlan;
import org.gms.data.repo.ModelRateRepository;
import org.gms.data.repo.PointAccountRepository;
import org.gms.data.repo.PointTransactionRepository;
import org.gms.data.repo.SubscriptionPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BillingService 计费核心逻辑：倍率扣费、plan 限额、签到每日限、免计费模型。 */
class BillingServiceTest {

    private MemoryPointAccountRepository pointAccountRepository;
    private MemoryModelRateRepository modelRateRepository;
    private MemoryPlanRepository planRepository;
    private MemoryTransactionRepository transactionRepository;
    private MemoryConfigFacade configFacade;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        pointAccountRepository = new MemoryPointAccountRepository();
        modelRateRepository = new MemoryModelRateRepository();
        planRepository = new MemoryPlanRepository();
        transactionRepository = new MemoryTransactionRepository();
        configFacade = new MemoryConfigFacade();
        billingService = new BillingService(pointAccountRepository, modelRateRepository,
                planRepository, transactionRepository, configFacade);
        modelRateRepository.insert(rate(1L, "deepseek-chat", 10000, 10000));
        modelRateRepository.insert(rate(2L, "local-rule", 0, 0));
    }

    @Test
    void charge_按模型倍率扣积分() {
        billingService.adjust(1L, 1000L, "admin_adjust");
        long points = billingService.charge(1L, "deepseek-chat", 100, 100, 0);
        assertThat(points).isEqualTo(200);
        assertThat(billingService.balance(1L).balance()).isEqualTo(800);
    }

    @Test
    void charge_本地规则模型免计费() {
        billingService.adjust(1L, 1000L, "admin_adjust");
        long points = billingService.charge(1L, "local-rule", 100, 100, 0);
        assertThat(points).isZero();
        assertThat(billingService.balance(1L).balance()).isEqualTo(1000);
    }

    @Test
    void charge_联网搜索按次额外扣分() {
        billingService.adjust(1L, 1000L, "admin_adjust");
        long points = billingService.charge(1L, "deepseek-chat", 0, 0, 3);
        assertThat(points).isEqualTo(3);
        assertThat(billingService.balance(1L).balance()).isEqualTo(997);
    }

    @Test
    void charge_联网搜索成本从配置中心读取() {
        configFacade.put("billing.websearch.cost", "5");
        billingService.adjust(1L, 1000L, "admin_adjust");
        long points = billingService.charge(1L, "deepseek-chat", 0, 0, 3);
        assertThat(points).isEqualTo(15);
        assertThat(billingService.balance(1L).balance()).isEqualTo(985);
    }

    @Test
    void precheck_无plan且余额不足_抛insufficient() {
        assertThatThrownBy(() -> billingService.precheck(1L))
                .isInstanceOf(BillingException.class)
                .satisfies(error -> assertThat(((BillingException) error).code())
                        .isEqualTo("insufficient_points"));
    }

    @Test
    void precheck_plan月限额已满_抛limit() {
        planRepository.insert(plan(1L, "pro", 100L, 0L, 0L));
        billingService.adjust(1L, 1000L, "admin_adjust");
        billingService.setPlan(1L, 1L);
        billingService.charge(1L, "deepseek-chat", 50, 50, 0); // 消耗 100 积分，触达月限额
        assertThatThrownBy(() -> billingService.precheck(1L))
                .isInstanceOf(BillingException.class)
                .satisfies(error -> assertThat(((BillingException) error).code())
                        .isEqualTo("billing_limit_exceeded"));
    }

    @Test
    void signin_每日一次_重复抛dailyLimit() {
        billingService.signin(1L);
        assertThat(billingService.balance(1L).balance()).isEqualTo(10L);
        assertThatThrownBy(() -> billingService.signin(1L))
                .isInstanceOf(BillingException.class)
                .satisfies(error -> assertThat(((BillingException) error).code())
                        .isEqualTo("daily_limit_exceeded"));
    }

    @Test
    void adjust_负数扣积分() {
        billingService.adjust(1L, 500L, "admin_adjust");
        billingService.adjust(1L, -200L, "admin_adjust");
        assertThat(billingService.balance(1L).balance()).isEqualTo(300L);
    }

    @Test
    void charge_落一条负流水() {
        billingService.adjust(1L, 1000L, "admin_adjust");
        billingService.charge(1L, "deepseek-chat", 10, 10, 0);
        assertThat(transactionRepository.records).hasSize(2);
        assertThat(transactionRepository.records.get(1).getReason()).isEqualTo("ai_consume");
        assertThat(transactionRepository.records.get(1).getChangeAmount()).isEqualTo(-20L);
    }

    private static ModelRate rate(Long id, String key, int input, int output) {
        ModelRate rate = new ModelRate();
        rate.setId(id);
        rate.setModelKey(key);
        rate.setInputRate(input);
        rate.setOutputRate(output);
        rate.setEnabled(1);
        return rate;
    }

    private static SubscriptionPlan plan(Long id, String code, long monthly, long weekly, long fiveHour) {
        SubscriptionPlan plan = new SubscriptionPlan();
        plan.setId(id);
        plan.setPlanCode(code);
        plan.setDisplayName(code);
        plan.setMonthlyLimit(monthly);
        plan.setWeeklyLimit(weekly);
        plan.setFiveHourLimit(fiveHour);
        plan.setPriceNx(0);
        plan.setEnabled(1);
        return plan;
    }

    private static final class MemoryPointAccountRepository implements PointAccountRepository {
        final Map<Long, PointAccount> byAccountId = new LinkedHashMap<>();

        public Optional<PointAccount> findByAccountId(Long accountId) {
            return Optional.ofNullable(byAccountId.get(accountId));
        }

        public void insert(PointAccount account) {
            byAccountId.put(account.getAccountId(), account);
        }

        public void update(PointAccount account) {
            byAccountId.put(account.getAccountId(), account);
        }

        public List<PointAccount> findAll() {
            return new ArrayList<>(byAccountId.values());
        }
    }

    private static final class MemoryModelRateRepository implements ModelRateRepository {
        final Map<String, ModelRate> byKey = new LinkedHashMap<>();

        public Optional<ModelRate> findByModelKey(String modelKey) {
            return Optional.ofNullable(byKey.get(modelKey));
        }

        public List<ModelRate> findAll() {
            return new ArrayList<>(byKey.values());
        }

        public void insert(ModelRate rate) {
            byKey.put(rate.getModelKey(), rate);
        }

        public void update(ModelRate rate) {
            byKey.put(rate.getModelKey(), rate);
        }
    }

    private static final class MemoryPlanRepository implements SubscriptionPlanRepository {
        final Map<Long, SubscriptionPlan> byId = new LinkedHashMap<>();

        public Optional<SubscriptionPlan> findById(Long id) {
            return Optional.ofNullable(byId.get(id));
        }

        public Optional<SubscriptionPlan> findByPlanCode(String planCode) {
            return byId.values().stream().filter(plan -> plan.getPlanCode().equals(planCode)).findFirst();
        }

        public List<SubscriptionPlan> findAll() {
            return new ArrayList<>(byId.values());
        }

        public void insert(SubscriptionPlan plan) {
            byId.put(plan.getId(), plan);
        }

        public void update(SubscriptionPlan plan) {
            byId.put(plan.getId(), plan);
        }
    }

    private static final class MemoryTransactionRepository implements PointTransactionRepository {
        final List<PointTransaction> records = new ArrayList<>();

        public void insert(PointTransaction transaction) {
            records.add(transaction);
        }

        public List<PointTransaction> findByAccountId(Long accountId) {
            return records.stream().filter(tx -> tx.getAccountId().equals(accountId)).toList();
        }

        public long countByAccountIdAndReasonSince(Long accountId, String reason, String since) {
            return records.stream()
                    .filter(tx -> tx.getAccountId().equals(accountId))
                    .filter(tx -> reason.equals(tx.getReason()))
                    .filter(tx -> tx.getCreatedAt() != null && tx.getCreatedAt().compareTo(since) >= 0)
                    .count();
        }
    }

    private static final class MemoryConfigFacade implements ConfigFacade {
        final Map<String, String> values = new LinkedHashMap<>();

        public long currentVersion() {
            return 0;
        }

        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String key, Class<T> type) {
            String raw = values.get(key);
            if (raw == null) {
                return Optional.empty();
            }
            if (type == Integer.class) {
                return (Optional<T>) Optional.of(Integer.parseInt(raw));
            }
            if (type == String.class) {
                return (Optional<T>) Optional.of(raw);
            }
            return (Optional<T>) Optional.of(raw);
        }

        public void signalChange() {
        }

        void put(String key, String value) {
            values.put(key, value);
        }
    }
}
