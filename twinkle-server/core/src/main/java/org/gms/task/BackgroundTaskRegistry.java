package org.gms.task;

import jakarta.inject.Singleton;
import org.gms.concurrent.ThreadManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台任务与定时任务的统一有界投影，供控制台监控、立即运行、启停和失败重试使用。
 * 任务输出只保留安全摘要，原始日志和请求正文不得进入这里。
 */
@Singleton
public final class BackgroundTaskRegistry {

    private static final int MAX_HISTORY = 200;

    private final Map<String, MutableSchedule> schedules = new ConcurrentHashMap<>();
    private final Map<String, TaskRun> runsById = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<TaskRun> recentRuns = new ConcurrentLinkedDeque<>();
    private final ThreadManager threadManager;

    public BackgroundTaskRegistry(ThreadManager threadManager) {
        this.threadManager = threadManager;
    }

    public void registerSchedule(String scheduleId, String taskType, String displayName,
                                 String source, String schedule, boolean enabled,
                                 boolean retryable, Runnable runner) {
        if (scheduleId == null || scheduleId.isBlank() || runner == null) {
            throw new IllegalArgumentException("scheduleId and runner are required");
        }
        schedules.compute(scheduleId, (ignored, current) -> {
            MutableSchedule target = current == null ? new MutableSchedule(scheduleId) : current;
            target.taskType = requireText(taskType, "taskType");
            target.displayName = requireText(displayName, "displayName");
            target.source = requireText(source, "source");
            target.schedule = requireText(schedule, "schedule");
            target.enabled = enabled;
            target.retryable = retryable;
            target.runner = runner;
            return target;
        });
    }

    public List<TaskSchedule> schedules() {
        return schedules.values().stream()
                .map(MutableSchedule::snapshot)
                .sorted(Comparator.comparing(TaskSchedule::scheduleId))
                .toList();
    }

    public List<TaskRun> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_HISTORY));
        List<TaskRun> result = new ArrayList<>(safeLimit);
        for (TaskRun run : recentRuns) {
            result.add(run);
            if (result.size() >= safeLimit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    public Optional<TaskRun> find(String taskId) {
        return Optional.ofNullable(runsById.get(taskId));
    }

    public Optional<TaskSchedule> setEnabled(String scheduleId, boolean enabled) {
        MutableSchedule schedule = schedules.get(scheduleId);
        if (schedule == null) {
            return Optional.empty();
        }
        schedule.enabled = enabled;
        if (!enabled) {
            schedule.nextRunAt = null;
        }
        return Optional.of(schedule.snapshot());
    }

    public Optional<TaskSchedule> updateNextRun(String scheduleId, Instant nextRunAt) {
        MutableSchedule schedule = schedules.get(scheduleId);
        if (schedule == null) {
            return Optional.empty();
        }
        schedule.nextRunAt = nextRunAt == null || !schedule.enabled ? null : nextRunAt.toString();
        return Optional.of(schedule.snapshot());
    }

    /** 汇总完整调度计数、当前有界历史状态和虚拟线程执行器状态。 */
    public TaskMetrics metrics() {
        long running = 0;
        long succeeded = 0;
        long failed = 0;
        long cancelled = 0;
        for (TaskRun run : recentRuns) {
            switch (run.status()) {
                case "running" -> running++;
                case "succeeded" -> succeeded++;
                case "failed" -> failed++;
                case "cancelled" -> cancelled++;
                default -> { }
            }
        }
        long totalRuns = schedules.values().stream().mapToLong(item -> item.runCount.get()).sum();
        long totalErrors = schedules.values().stream().mapToLong(item -> item.errorCount.get()).sum();
        return new TaskMetrics(schedules.size(), recentRuns.size(), running, succeeded, failed, cancelled,
                totalRuns, totalErrors, threadManager.snapshot());
    }

    /** 创建运行记录后立即返回，实际任务由统一虚拟线程管理器异步执行。 */
    public Optional<TaskRun> run(String scheduleId, String trigger, String subjectId, String requestId) {
        MutableSchedule schedule = schedules.get(scheduleId);
        if (schedule == null) {
            return Optional.empty();
        }
        if (!schedule.enabled && !"manual".equals(trigger) && !"retry".equals(trigger)) {
            return Optional.empty();
        }

        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "");
        Instant created = Instant.now();
        TaskRun running = new TaskRun(taskId, schedule.taskType, schedule.displayName,
                schedule.source, scheduleId, "running", trigger, created.toString(),
                null, null, null, 1, 1, false, schedule.retryable,
                requestId, subjectId, null, null, null, "virtual-thread-per-task", null);
        remember(running);

        try {
            threadManager.execute(() -> complete(schedule, running));
            return Optional.of(running);
        } catch (RuntimeException e) {
            Instant finished = Instant.now();
            TaskRun failed = new TaskRun(taskId, schedule.taskType, schedule.displayName,
                    schedule.source, scheduleId, "failed", trigger, created.toString(),
                    null, finished.toString(), null, 1, 1, false, schedule.retryable,
                    requestId, subjectId, e.getClass().getSimpleName(), safeSummary(e), null,
                    "virtual-thread-per-task", null);
            schedule.lastStatus = "failed";
            schedule.lastRunAt = failed.completedAt();
            schedule.runCount.incrementAndGet();
            schedule.errorCount.incrementAndGet();
            replace(failed);
            return Optional.of(failed);
        }
    }

    private void complete(MutableSchedule schedule, TaskRun running) {
        Instant created = Instant.parse(running.createdAt());
        Instant started = Instant.now();
        String threadName = Thread.currentThread().getName();
        TaskRun startedRun = new TaskRun(running.taskId(), schedule.taskType, schedule.displayName,
                schedule.source, running.scheduleId(), "running", running.trigger(), running.createdAt(),
                started.toString(), null, null, 1, 1, false, schedule.retryable,
                running.requestId(), running.subjectId(), null, null, durationMillis(created, started),
                "virtual-thread-per-task", threadName);
        replace(startedRun);
        TaskRun completed;
        try {
            schedule.runner.run();
            Instant finished = Instant.now();
            completed = new TaskRun(running.taskId(), schedule.taskType, schedule.displayName,
                    schedule.source, running.scheduleId(), "succeeded", running.trigger(), running.createdAt(),
                    started.toString(), finished.toString(), durationMillis(started, finished),
                    1, 1, false, schedule.retryable, running.requestId(), running.subjectId(), null, null,
                    durationMillis(created, started), "virtual-thread-per-task", threadName);
            schedule.lastStatus = "succeeded";
        } catch (RuntimeException e) {
            Instant finished = Instant.now();
            completed = new TaskRun(running.taskId(), schedule.taskType, schedule.displayName,
                    schedule.source, running.scheduleId(), "failed", running.trigger(), running.createdAt(),
                    started.toString(), finished.toString(), durationMillis(started, finished),
                    1, 1, false, schedule.retryable, running.requestId(), running.subjectId(),
                    e.getClass().getSimpleName(), safeSummary(e), durationMillis(created, started),
                    "virtual-thread-per-task", threadName);
            schedule.lastStatus = "failed";
            schedule.errorCount.incrementAndGet();
        }
        schedule.lastRunAt = completed.completedAt();
        schedule.runCount.incrementAndGet();
        replace(completed);
    }

    public Optional<TaskRun> retry(String taskId, String subjectId, String requestId) {
        TaskRun previous = runsById.get(taskId);
        if (previous == null || !previous.retryable() || !"failed".equals(previous.status())) {
            return Optional.empty();
        }
        return run(previous.scheduleId(), "retry", subjectId, requestId);
    }

    private void remember(TaskRun run) {
        runsById.put(run.taskId(), run);
        recentRuns.addFirst(run);
        while (recentRuns.size() > MAX_HISTORY) {
            TaskRun removed = recentRuns.pollLast();
            if (removed != null) {
                runsById.remove(removed.taskId(), removed);
            }
        }
    }

    private void replace(TaskRun run) {
        runsById.put(run.taskId(), run);
        recentRuns.removeIf(item -> item.taskId().equals(run.taskId()));
        recentRuns.addFirst(run);
    }

    private static long durationMillis(Instant started, Instant finished) {
        return Math.max(0, finished.toEpochMilli() - started.toEpochMilli());
    }

    private static String safeSummary(RuntimeException error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) {
            value = error.getClass().getSimpleName();
        }
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    public record TaskRun(String taskId, String taskType, String displayName, String source,
                          String scheduleId, String status, String trigger, String createdAt,
                          String startedAt, String completedAt, Long durationMs,
                          int attempt, int maxAttempts, boolean cancellable, boolean retryable,
                          String requestId, String subjectId, String errorCode, String errorSummary,
                          Long queueDelayMs, String executorType, String threadName) {
    }

    public record TaskMetrics(int registeredSchedules, int retainedRuns, long runningRuns,
                              long succeededRuns, long failedRuns, long cancelledRuns,
                              long totalRuns, long totalErrors, ThreadManager.Snapshot executor) {
    }

    public record TaskSchedule(String scheduleId, String taskType, String displayName, String source,
                               String schedule, boolean enabled, boolean retryable, String nextRunAt,
                               String lastRunAt, String lastStatus, long runCount, long errorCount) {
    }

    private static final class MutableSchedule {
        private final String scheduleId;
        private final AtomicLong runCount = new AtomicLong();
        private final AtomicLong errorCount = new AtomicLong();
        private volatile String taskType;
        private volatile String displayName;
        private volatile String source;
        private volatile String schedule;
        private volatile boolean enabled;
        private volatile boolean retryable;
        private volatile String nextRunAt;
        private volatile String lastRunAt;
        private volatile String lastStatus;
        private volatile Runnable runner;

        private MutableSchedule(String scheduleId) {
            this.scheduleId = scheduleId;
        }

        private TaskSchedule snapshot() {
            return new TaskSchedule(scheduleId, taskType, displayName, source, schedule, enabled,
                    retryable, nextRunAt, lastRunAt, lastStatus, runCount.get(), errorCount.get());
        }
    }
}
