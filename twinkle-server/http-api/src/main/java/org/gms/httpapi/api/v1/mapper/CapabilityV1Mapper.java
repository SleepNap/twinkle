package org.gms.httpapi.api.v1.mapper;

import org.gms.httpapi.api.v1.contract.ApiContract;
import org.gms.httpapi.api.v1.dto.response.CapabilityCatalogResponse;
import org.gms.httpapi.api.v1.dto.response.CapabilityDetailResponse;
import org.gms.httpapi.api.v1.dto.response.ToolSummaryResponse;
import org.gms.httpapi.capability.ToolCatalogService;

import java.util.List;
import java.util.Map;

/** 将能力服务的版本无关结果映射为 v1 契约。 */
public final class CapabilityV1Mapper {

    public static CapabilityCatalogResponse catalog(ToolCatalogService.Catalog catalog) {
        return new CapabilityCatalogResponse(
                ApiContract.VERSION,
                catalog.catalogVersion(),
                catalog.permissionVersion(),
                catalog.tools().stream().map(CapabilityV1Mapper::summary).toList(),
                catalog.generatedAt());
    }

    public static CapabilityDetailResponse detail(ToolCatalogService.ToolSpec spec) {
        Map<String, Object> detail = spec.detail();
        Map<String, Object> permission = map(detail, "permission");
        Map<String, Object> risk = map(detail, "risk");
        Map<String, Object> execution = map(detail, "execution");
        Map<String, Object> result = map(detail, "result");
        Map<String, Object> audit = map(detail, "audit");
        return new CapabilityDetailResponse(
                ApiContract.VERSION,
                string(detail, "toolId"),
                string(detail, "toolVersion"),
                string(detail, "title"),
                string(detail, "description"),
                string(detail, "provider"),
                string(detail, "availability"),
                string(detail, "schemaDialect"),
                map(detail, "inputSchema"),
                map(detail, "outputSchema"),
                new CapabilityDetailResponse.Permission(
                        strings(permission, "requiredScopes"),
                        strings(permission, "resourceTypes"),
                        string(permission, "resourceResolution")),
                new CapabilityDetailResponse.Risk(
                        string(risk, "level"), string(risk, "confirmation"),
                        bool(risk, "supportsDryRun")),
                new CapabilityDetailResponse.Execution(
                        string(execution, "mode"), integer(execution, "timeoutMs"),
                        string(execution, "idempotency"), string(execution, "retryPolicy")),
                new CapabilityDetailResponse.Result(
                        strings(result, "contentTypes"),
                        string(result, "dataClassification")),
                new CapabilityDetailResponse.Audit(
                        string(audit, "mode"), string(audit, "parameterSummary")));
    }

    private static ToolSummaryResponse summary(Map<String, Object> summary) {
        return new ToolSummaryResponse(
                string(summary, "toolId"),
                string(summary, "toolVersion"),
                string(summary, "title"),
                string(summary, "summary"),
                string(summary, "provider"),
                strings(summary, "categories"),
                strings(summary, "tags"),
                string(summary, "riskLevel"),
                string(summary, "availability"),
                string(summary, "permissionState"));
    }

    private static String string(Map<String, Object> source, String key) {
        return (String) source.get(key);
    }

    private static boolean bool(Map<String, Object> source, String key) {
        return (Boolean) source.get(key);
    }

    private static int integer(Map<String, Object> source, String key) {
        return ((Number) source.get(key)).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> source, String key) {
        return (List<String>) source.get(key);
    }

    private CapabilityV1Mapper() {
    }
}
