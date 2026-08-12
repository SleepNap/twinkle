package org.gms.httpapi.controller;

import io.micronaut.http.HttpRequest;
import org.gms.task.BackgroundTaskRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class AdminTaskControllerTest {

    @Test
    public void exposesSchedulesAndManualRuns() {
        BackgroundTaskRegistry registry = new BackgroundTaskRegistry();
        AtomicInteger calls = new AtomicInteger();
        registry.registerSchedule("daily", "report", "Daily report", "ai", "fixed-delay PT24H",
                true, true, calls::incrementAndGet);
        AdminTaskController controller = new AdminTaskController(registry);

        assertThat(controller.schedules().get("schedules")).asList().hasSize(1);
        assertThat(controller.run(HttpRequest.POST("/", ""), "daily").code()).isEqualTo(202);
        assertThat(calls).hasValue(1);
        assertThat(((java.util.List<?>) controller.tasks(50).get("tasks"))).hasSize(1);
    }

    @Test
    public void returnsNotFoundForUnknownSchedule() {
        AdminTaskController controller = new AdminTaskController(new BackgroundTaskRegistry());

        assertThat(controller.run(HttpRequest.POST("/", Map.of()), "missing").code()).isEqualTo(404);
    }
}
