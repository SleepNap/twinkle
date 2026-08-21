package org.gms.httpapi.controller;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.core.annotation.Nullable;
import org.gms.data.entity.Account;
import org.gms.data.entity.AiUsageEntity;
import org.gms.data.entity.AiUsagePolicy;
import org.gms.data.repo.AccountRepository;
import org.gms.data.repo.AiUsageRepository;
import org.gms.httpapi.auth.ApiKeyService;
import org.gms.httpapi.billing.AiPolicyService;
import org.gms.service.agent.AgentStatusService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web 控制台 AI 权限与预算管理 API（{@code /admin/v1/ai/*}）。
 *
 * <p>策略挂<b>账号维度</b>（{@code account_records.id}），与积分账户同口径——不是 subjectId：
 * 所有 API Key 的 subjectId 都继承签发者，控制台签发出来恒为 {@code subject_owner}，
 * 挂上去会塌缩成"全体一份"，预算形同虚设。
 *
 * <p>认证、RBAC（{@code admin.ai:manage}）、写操作 reason 强制与审计由 {@code AdminAuthFilter}
 * 通配 {@code /admin/v1/**} 自动覆盖，本类不重复接线。
 *
 * <p>AI 运行态经 core {@link AgentStatusService} 契约读取——管理面不依赖 ai 模块。
 */
@Controller("/admin/v1/ai")
@Produces(MediaType.APPLICATION_JSON)
public final class AiAdminController {

    /** 连续失败达此次数即视为降级（外部模型不可达时调用会持续抛错）。 */
    private static final int DEGRADED_FAILURE_THRESHOLD = 3;
    /** 用量查询单次返回上限，防控制台把全表拉回来。 */
    private static final int USAGE_QUERY_LIMIT = 500;

    private final AiPolicyService policyService;
    private final AgentStatusService agentStatus;
    private final AiUsageRepository usageRepository;
    private final AccountRepository accountRepository;
    private final ApiKeyService apiKeyService;

    public AiAdminController(AiPolicyService policyService, AgentStatusService agentStatus,
                             AiUsageRepository usageRepository, AccountRepository accountRepository,
                             ApiKeyService apiKeyService) {
        this.policyService = policyService;
        this.agentStatus = agentStatus;
        this.usageRepository = usageRepository;
        this.accountRepository = accountRepository;
        this.apiKeyService = apiKeyService;
    }

    /** AI 运行态：模型、连通性、降级、最近错误，以及全局开关与白名单。 */
    @Get("/status")
    public Map<String, Object> status() {
        AiPolicyService.GlobalPolicy global = policyService.globalPolicy();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", agentStatus.available());
        result.put("model", agentStatus.modelDescriptor());
        result.put("externalModel", agentStatus.externalModel());
        result.put("callCount", agentStatus.callCount());
        result.put("consecutiveFailures", agentStatus.consecutiveFailures());
        result.put("degraded", agentStatus.consecutiveFailures() >= DEGRADED_FAILURE_THRESHOLD);
        result.put("lastError", agentStatus.lastError());
        result.put("lastErrorAt", agentStatus.lastErrorAt());
        result.put("runtimeEnabled", global.runtimeEnabled());
        result.put("allowedModels", global.allowedModels());
        return result;
    }

    /** 账号策略列表；带 accountId 时只返回该账号（无策略行则返回空列表）。 */
    @Get("/policies")
    public Map<String, Object> policies(@QueryValue @Nullable Long accountId) {
        List<AiUsagePolicy> policies = accountId == null
                ? policyService.findAll()
                : policyService.find(accountId).map(List::of).orElse(List.of());
        return Map.of("policies", policies.stream().map(this::policyMap).toList());
    }

    /**
     * 新增或更新账号策略。
     *
     * <p>写入后刷新该账号名下所有 API Key 的 permissionVersion。注意这<b>不是</b>策略生效机制
     * ——治理层每次调用实时查库，DB 一写即生效；刷新只让客户端的能力目录缓存失效，
     * 并让后续审计记录带上新的 policyVersion。
     */
    @Put("/policies/{accountId}")
    public HttpResponse<?> upsertPolicy(@PathVariable long accountId,
                                        @Body Map<String, Object> body) {
        if (accountRepository.findById(accountId).isEmpty()) {
            return HttpResponse.badRequest(Map.of("error", "account_not_found",
                    "message", "account " + accountId + " does not exist"));
        }
        AiUsagePolicy policy = policyService.upsert(accountId,
                intField(body, "enabled", 1),
                body.get("allowedModels") == null ? "" : String.valueOf(body.get("allowedModels")),
                longField(body, "dailyPointLimit", 0L),
                longField(body, "dailyCallLimit", 0L),
                longField(body, "dailyTokenLimit", 0L),
                body.get("updatedBy") == null ? "" : String.valueOf(body.get("updatedBy")));
        int refreshed = apiKeyService.refreshPermissionVersionByAccount(accountId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("saved", true);
        result.put("policy", policyMap(policy));
        result.put("refreshedKeys", refreshed);
        return HttpResponse.ok(result);
    }

    /** AI 用量明细（按时间区间 + 账号过滤）。 */
    @Get("/usage")
    public Map<String, Object> usage(@QueryValue @Nullable String from,
                                     @QueryValue @Nullable String to,
                                     @QueryValue @Nullable Long accountId) {
        List<AiUsageEntity> records =
                usageRepository.findByRange(from, to, accountId, USAGE_QUERY_LIMIT);
        long totalPoints = records.stream().mapToLong(r -> value(r.getPointsCost())).sum();
        long totalTokens = records.stream()
                .mapToLong(r -> value(r.getInputTokens()) + value(r.getOutputTokens())).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records.stream().map(this::usageMap).toList());
        result.put("totalCalls", records.size());
        result.put("totalPoints", totalPoints);
        result.put("totalTokens", totalTokens);
        result.put("limit", USAGE_QUERY_LIMIT);
        result.put("truncated", records.size() >= USAGE_QUERY_LIMIT);
        return result;
    }

    private Map<String, Object> policyMap(AiUsagePolicy policy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountId", policy.getAccountId());
        result.put("accountName", accountRepository.findById(policy.getAccountId())
                .map(Account::getName).orElse(""));
        result.put("enabled", policy.getEnabled() == null || policy.getEnabled() != 0);
        result.put("allowedModels", policy.getAllowedModels());
        result.put("dailyPointLimit", policy.getDailyPointLimit());
        result.put("dailyCallLimit", policy.getDailyCallLimit());
        result.put("dailyTokenLimit", policy.getDailyTokenLimit());
        result.put("dailyPointUsed", policy.getDailyPointUsed());
        result.put("dailyCallUsed", policy.getDailyCallUsed());
        result.put("dailyTokenUsed", policy.getDailyTokenUsed());
        result.put("windowStart", policy.getWindowStart());
        result.put("updatedAt", policy.getUpdatedAt());
        result.put("updatedBy", policy.getUpdatedBy());
        return result;
    }

    private Map<String, Object> usageMap(AiUsageEntity usage) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", usage.getId());
        result.put("toolName", usage.getToolName());
        result.put("model", usage.getModel());
        result.put("inputTokens", usage.getInputTokens());
        result.put("outputTokens", usage.getOutputTokens());
        result.put("pointsCost", usage.getPointsCost());
        result.put("accountId", usage.getAccountId());
        result.put("elapsedMs", usage.getElapsedMs());
        result.put("createdAt", usage.getCreatedAt());
        return result;
    }

    private static long value(Integer number) {
        return number == null ? 0L : number;
    }

    private static Integer intField(Map<String, Object> body, String key, int fallback) {
        Object raw = body.get(key);
        if (raw instanceof Boolean flag) {
            return flag ? 1 : 0;
        }
        return raw instanceof Number number ? number.intValue() : fallback;
    }

    private static Long longField(Map<String, Object> body, String key, long fallback) {
        Object raw = body.get(key);
        return raw instanceof Number number ? number.longValue() : fallback;
    }
}
