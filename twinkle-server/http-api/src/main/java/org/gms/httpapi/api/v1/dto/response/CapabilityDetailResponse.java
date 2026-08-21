package org.gms.httpapi.api.v1.dto.response;

import java.util.List;
import java.util.Map;

/**
 * v1 Tool 能力详情。inputSchema/outputSchema 是开放的 JSON Schema，外围契约保持强类型。
 */
public record CapabilityDetailResponse(
        String contractVersion,
        String toolId,
        String toolVersion,
        String title,
        String description,
        String provider,
        String availability,
        String schemaDialect,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        Permission permission,
        Risk risk,
        Execution execution,
        Result result,
        Audit audit) {

    public record Permission(List<String> requiredScopes, List<String> resourceTypes,
                             String resourceResolution) {
    }

    public record Risk(String level, String confirmation, boolean supportsDryRun) {
    }

    public record Execution(String mode, int timeoutMs, String idempotency, String retryPolicy) {
    }

    public record Result(List<String> contentTypes, String dataClassification) {
    }

    public record Audit(String mode, String parameterSummary) {
    }
}
