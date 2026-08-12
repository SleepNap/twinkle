package org.gms.httpapi.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.gms.task.BackgroundTaskRegistry;

import java.util.Map;

/** Admin projection for bounded background-task history and registered schedules. */
@Controller("/admin/v1")
@Produces(MediaType.APPLICATION_JSON)
@ExecuteOn(TaskExecutors.BLOCKING)
public final class AdminTaskController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final BackgroundTaskRegistry registry;

    public AdminTaskController(BackgroundTaskRegistry registry) {
        this.registry = registry;
    }

    @Get("/tasks{?limit}")
    public Map<String, Object> tasks(@QueryValue(defaultValue = "50") int limit) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return Map.of("limit", safeLimit, "tasks", registry.recent(safeLimit));
    }

    @Get("/tasks/{taskId}")
    public HttpResponse<?> task(@PathVariable String taskId) {
        return registry.find(taskId)
                .<HttpResponse<?>>map(HttpResponse::ok)
                .orElseGet(() -> HttpResponse.notFound(Map.of("error", "task_not_found")));
    }

    @Post("/tasks/{taskId}/retry")
    public HttpResponse<?> retry(HttpRequest<?> request, @PathVariable String taskId) {
        return registry.retry(taskId, subject(request), requestId(request))
                .<HttpResponse<?>>map(run -> HttpResponse.status(HttpStatus.ACCEPTED).body(run))
                .orElseGet(() -> HttpResponse.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "task_not_retryable")));
    }

    @Get("/schedules")
    public Map<String, Object> schedules() {
        return Map.of("schedules", registry.schedules());
    }

    @Post("/schedules/{scheduleId}/run")
    public HttpResponse<?> run(HttpRequest<?> request, @PathVariable String scheduleId) {
        return registry.run(scheduleId, "manual", subject(request), requestId(request))
                .<HttpResponse<?>>map(value -> HttpResponse.status(HttpStatus.ACCEPTED).body(value))
                .orElseGet(() -> HttpResponse.notFound(Map.of("error", "schedule_not_found")));
    }

    @Put("/schedules/{scheduleId}/enabled")
    public HttpResponse<?> enabled(@PathVariable String scheduleId, @Body EnabledRequest body) {
        return registry.setEnabled(scheduleId, body.enabled())
                .<HttpResponse<?>>map(HttpResponse::ok)
                .orElseGet(() -> HttpResponse.notFound(Map.of("error", "schedule_not_found")));
    }

    private static String requestId(HttpRequest<?> request) {
        return request.getHeaders().get("X-Request-Id");
    }

    private static String subject(HttpRequest<?> request) {
        return request.getHeaders().get("X-Operator-Id");
    }

    public record EnabledRequest(boolean enabled) {
    }
}
