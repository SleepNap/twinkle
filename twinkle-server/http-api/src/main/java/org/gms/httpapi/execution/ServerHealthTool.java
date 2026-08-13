package org.gms.httpapi.execution;

import org.gms.httpapi.identity.ServerIdentity;
import org.gms.i18n.I18n;
import org.gms.observability.HealthIndicator;
import org.gms.observability.HealthRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** server.health.read：对内部 HealthRegistry 做允许列表和安全映射。 */
public final class ServerHealthTool {

    private static final Set<String> ALLOWED_CHECKS = Set.of(
            "liveness", "readiness", "db", "coordinator", "wz");

    private final HealthRegistry healthRegistry;
    private final ServerIdentity serverIdentity;

    public ServerHealthTool(HealthRegistry healthRegistry, ServerIdentity serverIdentity) {
        this.healthRegistry = healthRegistry;
        this.serverIdentity = serverIdentity;
    }

    public Map<String, Object> read(String requestId, String executionId) {
        final Map<String, HealthIndicator.Status> statuses;
        try {
            statuses = healthRegistry.statuses();
        } catch (RuntimeException e) {
            throw new ToolProtocolException(io.micronaut.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "tool_unavailable", I18n.message("error.health.unavailable"), true, executionId, requestId, Map.of());
        }

        List<Map<String, Object>> checks = new ArrayList<>();
        statuses.entrySet().stream()
                .filter(entry -> ALLOWED_CHECKS.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    LinkedHashMap<String, Object> check = new LinkedHashMap<>();
                    check.put("id", entry.getKey());
                    check.put("status", entry.getValue() == HealthIndicator.Status.UP ? "up" : "down");
                    check.put("message", null);
                    checks.add(check);
                });

        String overall;
        if (checks.isEmpty()) {
            overall = "unknown";
        } else if (checks.stream().allMatch(check -> "up".equals(check.get("status")))) {
            overall = "healthy";
        } else {
            overall = "unhealthy";
        }

        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        output.put("server", serverIdentity.toSafeMap());
        output.put("status", overall);
        output.put("checks", checks);
        output.put("observedAt", Instant.now().toString());
        return output;
    }
}
