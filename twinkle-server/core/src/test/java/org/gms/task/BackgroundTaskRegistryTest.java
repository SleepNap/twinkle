package org.gms.task;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class BackgroundTaskRegistryTest {

    @Test
    public void recordsSuccessfulRunAndScheduleCounters() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.registerSchedule("daily", "report", "Daily report", "core", "fixed-delay PT24H",
                true, true, calls::incrementAndGet);

        BackgroundTaskRegistry.TaskRun run = registry.run("daily", "manual", "operator", "req-1")
                .orElseThrow();

        assertThat(run.status()).isEqualTo("succeeded");
        assertThat(calls).hasValue(1);
        assertThat(registry.schedules().getFirst().runCount()).isEqualTo(1);
        assertThat(registry.find(run.taskId())).contains(run);
    }

    @Test
    public void capturesBoundedFailureSummaryAndAllowsRetry() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.registerSchedule("unstable", "maintenance", "Unstable", "test", "manual",
                true, true, () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("temporary failure");
                    }
                });

        BackgroundTaskRegistry.TaskRun failed = registry.run("unstable", "manual", null, null)
                .orElseThrow();
        BackgroundTaskRegistry.TaskRun retried = registry.retry(failed.taskId(), "operator", "req-2")
                .orElseThrow();

        assertThat(failed.status()).isEqualTo("failed");
        assertThat(failed.errorSummary()).isEqualTo("temporary failure");
        assertThat(retried.status()).isEqualTo("succeeded");
        assertThat(registry.schedules().getFirst().errorCount()).isEqualTo(1);
    }
}
