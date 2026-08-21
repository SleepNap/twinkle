package org.gms.task;

import org.gms.concurrent.ThreadManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class BackgroundTaskRegistryTest {

    @Test
    public void recordsSuccessfulRunAndScheduleCounters() {
        try (ThreadManager threadManager = new ThreadManager()) {
            BackgroundTaskRegistry registry = new BackgroundTaskRegistry(threadManager);
            AtomicInteger calls = new AtomicInteger();
            registry.registerSchedule("daily", "report", "Daily report", "core", "fixed-delay PT24H",
                    true, true, calls::incrementAndGet);

            BackgroundTaskRegistry.TaskRun run = registry.run("daily", "manual", "operator", "req-1")
                    .orElseThrow();

            assertThat(run.status()).isEqualTo("running");
            await().untilAsserted(() -> {
                assertThat(calls).hasValue(1);
                assertThat(registry.schedules().getFirst().runCount()).isEqualTo(1);
                assertThat(registry.find(run.taskId()).orElseThrow().status()).isEqualTo("succeeded");
                assertThat(registry.find(run.taskId()).orElseThrow().executorType())
                        .isEqualTo("virtual-thread-per-task");
                assertThat(registry.find(run.taskId()).orElseThrow().threadName())
                        .startsWith("twinkle-worker-");
                assertThat(registry.metrics().executor().virtualThreads()).isTrue();
            });
        }
    }

    @Test
    public void capturesBoundedFailureSummaryAndAllowsRetry() {
        try (ThreadManager threadManager = new ThreadManager()) {
            BackgroundTaskRegistry registry = new BackgroundTaskRegistry(threadManager);
            AtomicInteger calls = new AtomicInteger();
            registry.registerSchedule("unstable", "maintenance", "Unstable", "test", "manual",
                    true, true, () -> {
                        if (calls.incrementAndGet() == 1) {
                            throw new IllegalStateException("temporary failure");
                        }
                    });

            BackgroundTaskRegistry.TaskRun submitted = registry.run("unstable", "manual", null, null)
                    .orElseThrow();
            await().untilAsserted(() ->
                    assertThat(registry.find(submitted.taskId()).orElseThrow().status()).isEqualTo("failed"));
            BackgroundTaskRegistry.TaskRun failed = registry.find(submitted.taskId()).orElseThrow();
            BackgroundTaskRegistry.TaskRun retried = registry.retry(failed.taskId(), "operator", "req-2")
                    .orElseThrow();

            assertThat(failed.errorSummary()).isEqualTo("temporary failure");
            assertThat(retried.status()).isEqualTo("running");
            await().untilAsserted(() -> {
                assertThat(registry.find(retried.taskId()).orElseThrow().status()).isEqualTo("succeeded");
                assertThat(registry.schedules().getFirst().errorCount()).isEqualTo(1);
            });
        }
    }

    @Test
    public void recordsRejectedSubmissionAsFailedInsteadOfLeavingRunningRecord() {
        ThreadManager threadManager = new ThreadManager();
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry(threadManager);
        registry.registerSchedule("shutdown", "maintenance", "Shutdown", "test", "manual",
                true, true, () -> {
                });
        threadManager.close();

        BackgroundTaskRegistry.TaskRun run = registry.run("shutdown", "manual", null, null)
                .orElseThrow();

        assertThat(run.status()).isEqualTo("failed");
        assertThat(run.errorCode()).isEqualTo("RejectedExecutionException");
        assertThat(registry.metrics().runningRuns()).isZero();
        assertThat(registry.metrics().failedRuns()).isEqualTo(1);
        assertThat(registry.metrics().executor().rejectedTasks()).isEqualTo(1);
    }
}
