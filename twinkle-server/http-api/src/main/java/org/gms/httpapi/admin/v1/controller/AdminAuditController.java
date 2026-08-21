package org.gms.httpapi.admin.v1.controller;

import org.gms.httpapi.version.ApiRoutes;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import org.gms.data.repo.ApiRequestAuditRepository;
import org.gms.data.repo.ToolExecutionAuditRepository;

import java.util.Map;

/** Web 控制台审计查询 API；只返回安全摘要，不包含 Credential 或 Tool 原始输出。 */
@Controller(ApiRoutes.ADMIN_V1 + "/audits")
@Produces(MediaType.APPLICATION_JSON)
public final class AdminAuditController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ApiRequestAuditRepository apiRequestAudits;
    private final ToolExecutionAuditRepository toolExecutionAudits;

    public AdminAuditController(ApiRequestAuditRepository apiRequestAudits,
                                ToolExecutionAuditRepository toolExecutionAudits) {
        this.apiRequestAudits = apiRequestAudits;
        this.toolExecutionAudits = toolExecutionAudits;
    }

    /** 最近 API 请求审计。 */
    @Get("/api-requests{?limit}")
    public Map<String, Object> apiRequests(@QueryValue(defaultValue = "50") int limit) {
        int safeLimit = boundedLimit(limit);
        return Map.of(
                "total", apiRequestAudits.count(),
                "limit", safeLimit,
                "records", apiRequestAudits.findRecent(safeLimit));
    }

    /** 最近 Tool 执行审计。 */
    @Get("/tool-executions{?limit}")
    public Map<String, Object> toolExecutions(@QueryValue(defaultValue = "50") int limit) {
        int safeLimit = boundedLimit(limit);
        return Map.of(
                "total", toolExecutionAudits.count(),
                "limit", safeLimit,
                "records", toolExecutionAudits.findRecent(safeLimit));
    }

    private static int boundedLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
