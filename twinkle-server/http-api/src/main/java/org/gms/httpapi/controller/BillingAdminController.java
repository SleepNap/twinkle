package org.gms.httpapi.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import org.gms.data.entity.Account;
import org.gms.data.entity.ModelRate;
import org.gms.data.entity.PointAccount;
import org.gms.data.entity.PointTransaction;
import org.gms.data.entity.SubscriptionPlan;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.ModelRateRepository;
import org.gms.data.repo.PointAccountRepository;
import org.gms.data.repo.PointTransactionRepository;
import org.gms.data.repo.SubscriptionPlanRepository;
import org.gms.httpapi.billing.BillingException;
import org.gms.httpapi.billing.BillingService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 控制台积分计费管理 API（/admin/v1/billing/*，loopback，供积分额度页）。
 *
 * <p>与 {@code /admin/v1} 其他端点同待遇：内网/loopback 绑定，不套能力面限流，强鉴权留后续。
 */
@Controller("/admin/v1/billing")
@Produces(MediaType.APPLICATION_JSON)
public final class BillingAdminController {

    private final BillingService billingService;
    private final AccountRepository accountRepository;
    private final PointAccountRepository pointAccountRepository;
    private final ModelRateRepository modelRateRepository;
    private final SubscriptionPlanRepository planRepository;
    private final PointTransactionRepository transactionRepository;

    public BillingAdminController(BillingService billingService, AccountRepository accountRepository,
                                  PointAccountRepository pointAccountRepository,
                                  ModelRateRepository modelRateRepository,
                                  SubscriptionPlanRepository planRepository,
                                  PointTransactionRepository transactionRepository) {
        this.billingService = billingService;
        this.accountRepository = accountRepository;
        this.pointAccountRepository = pointAccountRepository;
        this.modelRateRepository = modelRateRepository;
        this.planRepository = planRepository;
        this.transactionRepository = transactionRepository;
    }

    /** 账号积分额度列表（含账号名、余额、plan）。 */
    @Get("/accounts")
    public Map<String, Object> accounts() {
        List<Map<String, Object>> accounts = pointAccountRepository.findAll().stream()
                .map(this::accountSummary)
                .toList();
        return Map.of("accounts", accounts);
    }

    /** 单个账号额度详情（余额 + plan + 三窗口）。 */
    @Get("/accounts/{accountId}")
    public Map<String, Object> account(@PathVariable long accountId) {
        BillingService.PointBalance balance = billingService.balance(accountId);
        String name = accountRepository.findById(accountId).map(Account::getName).orElse("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", accountId);
        result.put("name", name);
        result.put("balance", balance.balance());
        result.put("planId", balance.planId());
        result.put("planCode", balance.planCode());
        result.put("monthlyLimit", balance.monthlyLimit());
        result.put("monthlyUsed", balance.monthlyUsed());
        result.put("weeklyLimit", balance.weeklyLimit());
        result.put("weeklyUsed", balance.weeklyUsed());
        result.put("fiveHourLimit", balance.fiveHourLimit());
        result.put("fiveHourUsed", balance.fiveHourUsed());
        return result;
    }

    /** 管理员调账（amount 正数加积分，负数扣积分）。 */
    @Post("/accounts/{accountId}/adjust")
    public HttpResponse<?> adjust(@PathVariable long accountId, @Body Map<String, Object> body) {
        long amount = longField(body, "amount", 0L);
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        try {
            billingService.adjust(accountId, amount, reason);
        } catch (BillingException e) {
            return HttpResponse.badRequest(Map.of("error", e.code(), "message", e.getMessage()));
        }
        return HttpResponse.ok(Map.of("adjusted", true, "accountId", accountId));
    }

    /** 设置账号订阅的 plan（planId 为 null 表示取消订阅）。 */
    @Post("/accounts/{accountId}/plan")
    public HttpResponse<?> setPlan(@PathVariable long accountId, @Body Map<String, Object> body) {
        Long planId = body.get("planId") == null ? null : ((Number) body.get("planId")).longValue();
        billingService.setPlan(accountId, planId);
        return HttpResponse.ok(Map.of("set", true, "accountId", accountId));
    }

    /** 某账号的积分流水。 */
    @Get("/transactions")
    public Map<String, Object> transactions(@QueryValue long accountId) {
        List<Map<String, Object>> result = transactionRepository.findByAccountId(accountId).stream()
                .map(this::transactionMap)
                .toList();
        return Map.of("transactions", result);
    }

    /** 订阅计划列表。 */
    @Get("/plans")
    public Map<String, Object> plans() {
        return Map.of("plans", planRepository.findAll().stream().map(this::planMap).toList());
    }

    /** 创建/更新订阅计划（按 planCode upsert）。 */
    @Post("/plans")
    public HttpResponse<?> upsertPlan(@Body Map<String, Object> body) {
        String planCode = stringField(body, "planCode");
        if (planCode == null || planCode.isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "plan_code_required"));
        }
        SubscriptionPlan plan = planRepository.findByPlanCode(planCode).orElseGet(SubscriptionPlan::new);
        plan.setPlanCode(planCode);
        if (body.get("displayName") != null) {
            plan.setDisplayName(String.valueOf(body.get("displayName")));
        }
        if (body.get("monthlyLimit") != null) {
            plan.setMonthlyLimit(((Number) body.get("monthlyLimit")).longValue());
        }
        if (body.get("weeklyLimit") != null) {
            plan.setWeeklyLimit(((Number) body.get("weeklyLimit")).longValue());
        }
        if (body.get("fiveHourLimit") != null) {
            plan.setFiveHourLimit(((Number) body.get("fiveHourLimit")).longValue());
        }
        if (body.get("priceNx") != null) {
            plan.setPriceNx(((Number) body.get("priceNx")).intValue());
        }
        if (body.get("enabled") != null) {
            plan.setEnabled(((Number) body.get("enabled")).intValue());
        }
        if (plan.getId() == null) {
            planRepository.insert(plan);
        } else {
            planRepository.update(plan);
        }
        return HttpResponse.ok(planMap(plan));
    }

    /** 模型倍率列表。 */
    @Get("/model-rates")
    public Map<String, Object> modelRates() {
        return Map.of("rates", modelRateRepository.findAll().stream().map(this::rateMap).toList());
    }

    /** 创建/更新模型倍率（按 modelKey upsert）。 */
    @Post("/model-rates")
    public HttpResponse<?> upsertRate(@Body Map<String, Object> body) {
        String modelKey = stringField(body, "modelKey");
        if (modelKey == null || modelKey.isBlank()) {
            return HttpResponse.badRequest(Map.of("error", "model_key_required"));
        }
        ModelRate rate = modelRateRepository.findByModelKey(modelKey).orElseGet(ModelRate::new);
        rate.setModelKey(modelKey);
        if (body.get("inputRate") != null) {
            rate.setInputRate(((Number) body.get("inputRate")).intValue());
        }
        if (body.get("outputRate") != null) {
            rate.setOutputRate(((Number) body.get("outputRate")).intValue());
        }
        if (body.get("enabled") != null) {
            rate.setEnabled(((Number) body.get("enabled")).intValue());
        }
        if (rate.getId() == null) {
            modelRateRepository.insert(rate);
        } else {
            modelRateRepository.update(rate);
        }
        return HttpResponse.ok(rateMap(rate));
    }

    private Map<String, Object> accountSummary(PointAccount account) {
        String name = accountRepository.findById(account.getAccountId()).map(Account::getName).orElse("");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", account.getAccountId());
        result.put("name", name);
        result.put("balance", account.getBalance());
        result.put("planId", account.getPlanId());
        return result;
    }

    private Map<String, Object> transactionMap(PointTransaction transaction) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", transaction.getId());
        result.put("accountId", transaction.getAccountId());
        result.put("changeAmount", transaction.getChangeAmount());
        result.put("balanceAfter", transaction.getBalanceAfter());
        result.put("reason", transaction.getReason());
        result.put("detail", transaction.getDetail());
        result.put("createdAt", transaction.getCreatedAt());
        return result;
    }

    private Map<String, Object> planMap(SubscriptionPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", plan.getId());
        result.put("planCode", plan.getPlanCode());
        result.put("displayName", plan.getDisplayName());
        result.put("monthlyLimit", plan.getMonthlyLimit());
        result.put("weeklyLimit", plan.getWeeklyLimit());
        result.put("fiveHourLimit", plan.getFiveHourLimit());
        result.put("priceNx", plan.getPriceNx());
        result.put("enabled", plan.getEnabled());
        return result;
    }

    private Map<String, Object> rateMap(ModelRate rate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", rate.getId());
        result.put("modelKey", rate.getModelKey());
        result.put("inputRate", rate.getInputRate());
        result.put("outputRate", rate.getOutputRate());
        result.put("enabled", rate.getEnabled());
        return result;
    }

    private static String stringField(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private static long longField(Map<String, Object> body, String field, long fallback) {
        Object value = body.get(field);
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
