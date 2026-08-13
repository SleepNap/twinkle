package org.gms.httpapi.billing;

import lombok.extern.log4j.Log4j2;
import org.gms.config.ConfigFacade;
import org.gms.data.entity.ModelRate;
import org.gms.data.entity.PointAccount;
import org.gms.data.entity.PointTransaction;
import org.gms.data.entity.SubscriptionPlan;
import org.gms.data.repo.ModelRateRepository;
import org.gms.data.repo.PointAccountRepository;
import org.gms.data.repo.PointTransactionRepository;
import org.gms.data.repo.SubscriptionPlanRepository;
import org.gms.i18n.I18n;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 积分计费服务（账号层）：积分余额、模型倍率、plan 三档滚动限额、充值/签到/调账。
 *
 * <p>积分是账号维度资源（同一账号所有 API Key 共享）。AI 调用按 token×模型倍率扣积分，
 * 联网搜索按次扣固定积分；plan 用户受月/周/5h 三档滚动限额约束，无 plan 用户纯按余额扣。
 * 由 bootstrap 装配（不加 @Singleton），不依赖 ai/domain-game。
 */
@Log4j2
public final class BillingService {

    /** 倍率缩放因子：model_rate 的 input_rate/output_rate 放大 1e4 存整数。 */
    private static final long RATE_SCALE = 10_000L;
    /** 每日签到奖励积分。 */
    private static final long SIGNIN_POINTS = 10L;
    /** 每日签到次数上限。 */
    private static final int DAILY_SIGNIN_LIMIT = 1;
    /** 每日金币购买次数上限。 */
    private static final int DAILY_MESO_PURCHASE_LIMIT = 3;
    /** 联网搜索计费成本配置键（配置中心可调，默认 1 积分/次）。 */
    private static final String WEBSEARCH_COST_KEY = "billing.websearch.cost";

    private final PointAccountRepository pointAccountRepository;
    private final ModelRateRepository modelRateRepository;
    private final SubscriptionPlanRepository planRepository;
    private final PointTransactionRepository transactionRepository;
    private final ConfigFacade configFacade;

    public BillingService(PointAccountRepository pointAccountRepository,
                          ModelRateRepository modelRateRepository,
                          SubscriptionPlanRepository planRepository,
                          PointTransactionRepository transactionRepository,
                          ConfigFacade configFacade) {
        this.pointAccountRepository = pointAccountRepository;
        this.modelRateRepository = modelRateRepository;
        this.planRepository = planRepository;
        this.transactionRepository = transactionRepository;
        this.configFacade = configFacade;
    }

    /** 调用前粗检：余额或 plan 限额不足以支持一次调用时抛异常。 */
    public void precheck(Long accountId) {
        PointAccount account = getOrCreate(accountId);
        SubscriptionPlan plan = planFor(account);
        rollWindows(account, plan, Instant.now());
        if (plan != null) {
            if (windowExceeded(account, plan)) {
                throw new BillingException("billing_limit_exceeded", I18n.message("error.billing.plan_limit_exceeded"));
            }
        } else if (account.getBalance() <= 0) {
            throw new BillingException("insufficient_points", I18n.message("error.billing.insufficient_points"));
        }
    }

    /** 调用后按实际用量扣积分，返回扣除的积分数（0 表示免计费）。扣费失败不回滚 AI 结果。 */
    public long charge(Long accountId, String model, int inputTokens, int outputTokens,
                       int webSearchCount) {
        long points = computePoints(model, inputTokens, outputTokens)
                + webSearchPoints(webSearchCount);
        if (points <= 0) {
            return 0;
        }
        PointAccount account = getOrCreate(accountId);
        SubscriptionPlan plan = planFor(account);
        Instant now = Instant.now();
        rollWindows(account, plan, now);
        long newBalance = account.getBalance() - points;
        account.setBalance(newBalance);
        if (plan != null) {
            account.setMonthlyUsed(account.getMonthlyUsed() + points);
            account.setWeeklyUsed(account.getWeeklyUsed() + points);
            account.setFiveHourUsed(account.getFiveHourUsed() + points);
        }
        account.setUpdatedAt(now.toString());
        pointAccountRepository.update(account);
        insertTransaction(accountId, -points, newBalance, "ai_consume", null,
                "model=" + model + ",tokens=" + (inputTokens + outputTokens)
                        + ",websearch=" + webSearchCount);
        return points;
    }

    /** 查询账号余额 + plan + 三窗口限额汇总。 */
    public PointBalance balance(Long accountId) {
        PointAccount account = getOrCreate(accountId);
        SubscriptionPlan plan = planFor(account);
        return new PointBalance(accountId, account.getBalance(), account.getPlanId(),
                plan == null ? null : plan.getPlanCode(),
                plan == null ? null : plan.getMonthlyLimit(), account.getMonthlyUsed(),
                plan == null ? null : plan.getWeeklyLimit(), account.getWeeklyUsed(),
                plan == null ? null : plan.getFiveHourLimit(), account.getFiveHourUsed());
    }

    /** 管理员调账（正数为加积分，负数为扣积分）。 */
    public void adjust(Long accountId, long amount, String reason) {
        if (amount == 0) {
            throw new BillingException("invalid_input", I18n.message("error.billing.adjust_amount_zero"));
        }
        String safeReason = blankDefault(reason, "admin_adjust");
        if (amount > 0) {
            credit(accountId, amount, safeReason, "管理员调账");
        } else {
            debit(accountId, -amount, safeReason, "管理员调账");
        }
    }

    /** 每日签到领取免费积分。 */
    public void signin(Long accountId) {
        String today = utcDayStart(Instant.now());
        long count = transactionRepository.countByAccountIdAndReasonSince(
                accountId, "daily_signin", today);
        if (count >= DAILY_SIGNIN_LIMIT) {
            throw new BillingException("daily_limit_exceeded", I18n.message("error.billing.already_signed_in"));
        }
        credit(accountId, SIGNIN_POINTS, "daily_signin", "每日签到");
    }

    /** 抽象充值（channel 为 nx / meso）；金币购买受每日限。 */
    public void purchase(Long accountId, long points, String channel) {
        if (points <= 0) {
            throw new BillingException("invalid_input", I18n.message("error.billing.purchase_points_positive"));
        }
        String reason = switch (channel) {
            case "nx" -> "purchase_nx";
            case "meso" -> "purchase_meso";
            default -> throw new BillingException("invalid_input", I18n.message("error.billing.unsupported_channel", channel));
        };
        if ("meso".equals(channel)) {
            String today = utcDayStart(Instant.now());
            long count = transactionRepository.countByAccountIdAndReasonSince(
                    accountId, reason, today);
            if (count >= DAILY_MESO_PURCHASE_LIMIT) {
                throw new BillingException("daily_limit_exceeded", I18n.message("error.billing.meso_purchase_limit"));
            }
        }
        credit(accountId, points, reason, "充值");
    }

    /** 设置账号订阅的 plan（null 表示取消订阅）。 */
    public void setPlan(Long accountId, Long planId) {
        PointAccount account = getOrCreate(accountId);
        account.setPlanId(planId);
        account.setMonthlyUsed(0L);
        account.setWeeklyUsed(0L);
        account.setFiveHourUsed(0L);
        account.setMonthlyWindowStart(null);
        account.setWeeklyWindowStart(null);
        account.setFiveHourWindowStart(null);
        account.setUpdatedAt(Instant.now().toString());
        pointAccountRepository.update(account);
    }

    private void credit(Long accountId, long points, String reason, String detail) {
        PointAccount account = getOrCreate(accountId);
        long newBalance = account.getBalance() + points;
        account.setBalance(newBalance);
        account.setUpdatedAt(Instant.now().toString());
        pointAccountRepository.update(account);
        insertTransaction(accountId, points, newBalance, reason, null, detail);
    }

    private void debit(Long accountId, long points, String reason, String detail) {
        PointAccount account = getOrCreate(accountId);
        long newBalance = account.getBalance() - points;
        account.setBalance(newBalance);
        account.setUpdatedAt(Instant.now().toString());
        pointAccountRepository.update(account);
        insertTransaction(accountId, -points, newBalance, reason, null, detail);
    }

    private long computePoints(String model, int inputTokens, int outputTokens) {
        if (inputTokens <= 0 && outputTokens <= 0) {
            return 0;
        }
        ModelRate rate = modelRateRepository.findByModelKey(model).orElse(null);
        if (rate == null || Integer.valueOf(0).equals(rate.getEnabled())) {
            return 0;
        }
        long inputRate = rate.getInputRate() == null ? 0L : rate.getInputRate();
        long outputRate = rate.getOutputRate() == null ? 0L : rate.getOutputRate();
        long total = (long) inputTokens * inputRate + (long) outputTokens * outputRate;
        if (total <= 0) {
            return 0;
        }
        return (total + RATE_SCALE - 1) / RATE_SCALE;
    }

    private long webSearchPoints(int webSearchCount) {
        if (webSearchCount <= 0) {
            return 0;
        }
        int cost = configFacade.getOrDefault(WEBSEARCH_COST_KEY, 1);
        if (cost <= 0) {
            return 0;
        }
        return (long) webSearchCount * cost;
    }

    private PointAccount getOrCreate(Long accountId) {
        return pointAccountRepository.findByAccountId(accountId).orElseGet(() -> {
            PointAccount account = new PointAccount();
            account.setAccountId(accountId);
            account.setBalance(0L);
            account.setMonthlyUsed(0L);
            account.setWeeklyUsed(0L);
            account.setFiveHourUsed(0L);
            account.setCreatedAt(Instant.now().toString());
            pointAccountRepository.insert(account);
            return account;
        });
    }

    /** 滚动窗口：越过窗口周期则重置该窗口 used 并更新起始时间。 */
    private void rollWindows(PointAccount account, SubscriptionPlan plan, Instant now) {
        if (plan == null) {
            return;
        }
        boolean changed = false;
        if (account.getMonthlyWindowStart() == null
                || isAfter(account.getMonthlyWindowStart(), now, 30, ChronoUnit.DAYS)) {
            account.setMonthlyUsed(0L);
            account.setMonthlyWindowStart(now.toString());
            changed = true;
        }
        if (account.getWeeklyWindowStart() == null
                || isAfter(account.getWeeklyWindowStart(), now, 7, ChronoUnit.DAYS)) {
            account.setWeeklyUsed(0L);
            account.setWeeklyWindowStart(now.toString());
            changed = true;
        }
        if (account.getFiveHourWindowStart() == null
                || isAfter(account.getFiveHourWindowStart(), now, 5, ChronoUnit.HOURS)) {
            account.setFiveHourUsed(0L);
            account.setFiveHourWindowStart(now.toString());
            changed = true;
        }
        if (changed) {
            account.setUpdatedAt(now.toString());
            pointAccountRepository.update(account);
        }
    }

    private static boolean isAfter(String windowStart, Instant now, long amount, ChronoUnit unit) {
        try {
            return now.isAfter(Instant.parse(windowStart).plus(amount, unit));
        } catch (DateTimeParseException e) {
            return true;
        }
    }

    private boolean windowExceeded(PointAccount account, SubscriptionPlan plan) {
        return exceeds(account.getMonthlyUsed(), plan.getMonthlyLimit())
                || exceeds(account.getWeeklyUsed(), plan.getWeeklyLimit())
                || exceeds(account.getFiveHourUsed(), plan.getFiveHourLimit());
    }

    private static boolean exceeds(Long used, Long limit) {
        return limit != null && limit > 0 && used != null && used >= limit;
    }

    private SubscriptionPlan planFor(PointAccount account) {
        if (account.getPlanId() == null) {
            return null;
        }
        return planRepository.findById(account.getPlanId()).orElse(null);
    }

    private void insertTransaction(Long accountId, long change, long balanceAfter, String reason,
                                   String referenceId, String detail) {
        PointTransaction transaction = new PointTransaction();
        transaction.setAccountId(accountId);
        transaction.setChangeAmount(change);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setReason(reason);
        transaction.setReferenceId(referenceId);
        transaction.setDetail(detail);
        transaction.setCreatedAt(Instant.now().toString());
        transactionRepository.insert(transaction);
    }

    private static String utcDayStart(Instant now) {
        return now.truncatedTo(ChronoUnit.DAYS).toString();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 账号积分余额 + plan 限额快照。 */
    public record PointBalance(Long accountId, Long balance, Long planId, String planCode,
                               Long monthlyLimit, Long monthlyUsed,
                               Long weeklyLimit, Long weeklyUsed,
                               Long fiveHourLimit, Long fiveHourUsed) {
    }
}
