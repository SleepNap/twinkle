package org.gms.httpapi.billing;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.PointAccount;
import org.gms.data.entity.PointTransaction;
import org.gms.data.entity.SubscriptionPlan;
import org.gms.data.repo.PointAccountRepository;
import org.gms.data.repo.PointTransactionRepository;
import org.gms.data.repo.SubscriptionPlanRepository;
import org.gms.i18n.I18n;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 积分服务（账号层）：积分余额、plan 三档滚动限额、充值/签到/调账。
 *
 * <p>积分是账号维度资源（同一账号所有 API Key 共享）。由 bootstrap 装配
 * （不加 @Singleton），不依赖具体插件或游戏域实现。
 */
@Log4j2
public final class BillingService {

    /** 每日签到奖励积分。 */
    private static final long SIGNIN_POINTS = 10L;
    /** 每日签到次数上限。 */
    private static final int DAILY_SIGNIN_LIMIT = 1;
    /** 每日金币购买次数上限。 */
    private static final int DAILY_MESO_PURCHASE_LIMIT = 3;
    private final PointAccountRepository pointAccountRepository;
    private final SubscriptionPlanRepository planRepository;
    private final PointTransactionRepository transactionRepository;

    public BillingService(PointAccountRepository pointAccountRepository,
                          SubscriptionPlanRepository planRepository,
                          PointTransactionRepository transactionRepository) {
        this.pointAccountRepository = pointAccountRepository;
        this.planRepository = planRepository;
        this.transactionRepository = transactionRepository;
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
