package org.gms.ai.service;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.observability.Metrics;

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
public final class AiDailySummaryScheduler implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(AiDailySummaryScheduler.class);

    private final AiFacade aiFacade;
    private final Metrics metrics;
    private final AtomicLong lastRunEpoch = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private ScheduledExecutorService scheduler;

    public AiDailySummaryScheduler(AiFacade aiFacade, Metrics metrics) {
        this.aiFacade = aiFacade;
        this.metrics = metrics;
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
        scheduler.scheduleWithFixedDelay(this::runSummary, 60, TimeUnit.HOURS.toSeconds(24), TimeUnit.SECONDS);
        LOG.info("AI 每日总结调度已启动（首跑 60s 后，此后每 24h）");
    }

    /** 立即执行一次每日总结（手动触发，也供测试）。 */
    public void runSummary() {
        try {
            String report = aiFacade.onlineReport().getSummary();
            lastRunEpoch.set(System.currentTimeMillis());
            metrics.increment("ai.daily_summary.runs");
            metrics.gauge("ai.daily_summary.last_run", lastRunEpoch.get());
            LOG.info("AI 每日总结生成完成: {}", report);
        } catch (RuntimeException e) {
            errorCount.incrementAndGet();
            metrics.increment("ai.daily_summary.errors");
            LOG.error("AI 每日总结执行异常", e);
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
        }
    }
}
