package org.gms.ai.service;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.observability.Metrics;
import org.gms.task.BackgroundTaskRegistry;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 每日总结调度（架构 M3-2：每日总结 @Scheduled 场景）。
 *
 * <p>用原生 {@link ScheduledExecutorService}（与 MonsterSpawnService 一致，2C2G 单线程）。
 * 每日整点生成在线统计总结。可观测（记忆：调度器带监控钩子）：最近执行时间/异常计数
 * 经 {@link Metrics} 暴露，生命周期 start/close 显式管理。
 *
 * <p>装配由 bootstrap 接线（本类不加 @Singleton）。
 */
@Log4j2
public final class AiDailySummaryScheduler implements AutoCloseable {

    public static final String SCHEDULE_ID = "ai-daily-summary";



    private final AiFacade aiFacade;
    private final Metrics metrics;
    private final BackgroundTaskRegistry taskRegistry;
    private final AtomicLong lastRunEpoch = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private ScheduledExecutorService scheduler;

    public AiDailySummaryScheduler(AiFacade aiFacade, Metrics metrics) {
        this(aiFacade, metrics, new BackgroundTaskRegistry());
    }

    public AiDailySummaryScheduler(AiFacade aiFacade, Metrics metrics,
                                   BackgroundTaskRegistry taskRegistry) {
        this.aiFacade = aiFacade;
        this.metrics = metrics;
        this.taskRegistry = taskRegistry;
        taskRegistry.registerSchedule(SCHEDULE_ID, "ai-report", "AI daily online summary",
                "twinkle-ai", "fixed-delay PT24H", true, true, this::executeSummary);
    }

    /** 启动每日调度（bootstrap 装配时调用）。固定延迟 24 小时，首次 1 分钟验证。 */
    public void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ai-daily-summary");
            t.setDaemon(true);
            return t;
        });
        // 首次 60s 后跑一次（启动验证），之后每 24h
        taskRegistry.updateNextRun(SCHEDULE_ID, Instant.now().plusSeconds(60));
        scheduler.scheduleWithFixedDelay(this::runScheduledSummary, 60,
                TimeUnit.HOURS.toSeconds(24), TimeUnit.SECONDS);
        log.info(I18n.message("log.ai.daily_summary_started"));
    }

    /** 立即执行一次每日总结（手动触发，也供测试）。 */
    public void runSummary() {
        taskRegistry.run(SCHEDULE_ID, "manual", null, null);
    }

    private void runScheduledSummary() {
        taskRegistry.run(SCHEDULE_ID, "schedule", null, null);
        taskRegistry.updateNextRun(SCHEDULE_ID,
                Instant.now().plusSeconds(TimeUnit.HOURS.toSeconds(24)));
    }

    private void executeSummary() {
        try {
            String report = aiFacade.onlineReport().getSummary();
            lastRunEpoch.set(System.currentTimeMillis());
            metrics.increment("ai.daily_summary.runs");
            metrics.gauge("ai.daily_summary.last_run", lastRunEpoch.get());
            log.info(I18n.message("log.ai.daily_summary_completed"), report);
        } catch (RuntimeException e) {
            errorCount.incrementAndGet();
            metrics.increment("ai.daily_summary.errors");
            log.error(I18n.message("log.ai.daily_summary_error"), e);
            throw e;
        }
    }

    public long lastRunEpoch() {
        return lastRunEpoch.get();
    }

    public long errorCount() {
        return errorCount.get();
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
            taskRegistry.updateNextRun(SCHEDULE_ID, null);
        }
    }
}
