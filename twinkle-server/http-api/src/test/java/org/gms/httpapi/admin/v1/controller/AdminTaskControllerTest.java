package org.gms.httpapi.admin.v1.controller;

import io.micronaut.http.HttpRequest;
import org.gms.concurrent.ThreadManager;
import org.gms.task.BackgroundTaskRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class AdminTaskControllerTest {

    @Test
    public void exposesSchedulesAndManualRuns() {
        try (ThreadManager threadManager = new ThreadManager()) {
            BackgroundTaskRegistry registry = new BackgroundTaskRegistry(threadManager);
            AtomicInteger calls = new AtomicInteger();
            registry.registerSchedule("daily", "report", "Daily report", "ai", "fixed-delay PT24H",
                    true, true, calls::incrementAndGet);
            AdminTaskController controller = new AdminTaskController(registry);

            assertThat(controller.schedules().get("schedules")).asList().hasSize(1);
            assertThat(controller.run(HttpRequest.POST("/", ""), "daily").code()).isEqualTo(202);
            await().untilAsserted(() -> assertThat(calls).hasValue(1));
            assertThat(((java.util.List<?>) controller.tasks(50).get("tasks"))).hasSize(1);
        }
    }

    @Test
    public void returnsNotFoundForUnknownSchedule() {
        try (ThreadManager threadManager = new ThreadManager()) {
            AdminTaskController controller = new AdminTaskController(new BackgroundTaskRegistry(threadManager));

            assertThat(controller.run(HttpRequest.POST("/", Map.of()), "missing").code()).isEqualTo(404);
        }
    }
}
