package org.gms.task;

import jakarta.inject.Singleton;

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
 * Unified, bounded projection for scheduled and background work shown in the admin console.
 * Task output is deliberately limited to safe summaries; raw logs and request payloads do not belong here.
 */
@Singleton
public final class BackgroundTaskRegistry {

    private static final int MAX_HISTORY = 200;

    private final Map<String, MutableSchedule> schedules = new ConcurrentHashMap<>();
    private final Map<String, TaskRun> runsById = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<TaskRun> recentRuns = new ConcurrentLinkedDeque<>();

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

    /** Runs on the caller's executor. Controllers must dispatch this method to a blocking executor. */
    public Optional<TaskRun> run(String scheduleId, String trigger, String subjectId, String requestId) {
        MutableSchedule schedule = schedules.get(scheduleId);
        if (schedule == null) {
            return Optional.empty();
        }
        if (!schedule.enabled && !"manual".equals(trigger) && !"retry".equals(trigger)) {
            return Optional.empty();
        }

        String taskId = "task_" + UUID.randomUUID().toString().replace("-", "");
        Instant started = Instant.now();
        TaskRun running = new TaskRun(taskId, schedule.taskType, schedule.displayName,
                schedule.source, scheduleId, "running", trigger, started.toString(),
                started.toString(), null, null, 1, 1, false, schedule.retryable,
                requestId, subjectId, null, null);
        remember(running);

        TaskRun completed;
        try {
            schedule.runner.run();
            Instant finished = Instant.now();
            completed = new TaskRun(taskId, schedule.taskType, schedule.displayName,
                    schedule.source, scheduleId, "succeeded", trigger, started.toString(),
                    started.toString(), finished.toString(), durationMillis(started, finished),
                    1, 1, false, schedule.retryable, requestId, subjectId, null, null);
            schedule.lastStatus = "succeeded";
        } catch (RuntimeException e) {
            Instant finished = Instant.now();
            completed = new TaskRun(taskId, schedule.taskType, schedule.displayName,
                    schedule.source, scheduleId, "failed", trigger, started.toString(),
                    started.toString(), finished.toString(), durationMillis(started, finished),
                    1, 1, false, schedule.retryable, requestId, subjectId,
                    e.getClass().getSimpleName(), safeSummary(e));
            schedule.lastStatus = "failed";
            schedule.errorCount.incrementAndGet();
        }
        schedule.lastRunAt = completed.completedAt();
        schedule.runCount.incrementAndGet();
        replace(completed);
        return Optional.of(completed);
    }

    public Optional<TaskRun> retry(String taskId, String subjectId, String requestId) {
        TaskRun previous = runsById.get(taskId);
        if (previous == null || !previous.retryable()) {
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
                          String requestId, String subjectId, String errorCode, String errorSummary) {
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
