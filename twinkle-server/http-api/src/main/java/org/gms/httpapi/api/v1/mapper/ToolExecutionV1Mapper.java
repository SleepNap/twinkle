package org.gms.httpapi.api.v1.mapper;

import org.gms.httpapi.api.v1.dto.request.ToolClientContextRequest;
import org.gms.httpapi.api.v1.dto.request.ToolExecutionRequest;
import org.gms.httpapi.api.v1.dto.response.ToolExecutionResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v1 Tool 协议 DTO 与版本无关执行服务之间的映射。 */
public final class ToolExecutionV1Mapper {

    public static Map<String, Object> toServiceInput(ToolExecutionRequest request) {
        if (request == null) {
            return null;
        }
        LinkedHashMap<String, Object> body = new LinkedHashMap<>(request.additionalFields());
        body.put("contractVersion", request.getContractVersion());
        body.put("requestId", request.getRequestId());
        body.put("taskId", request.getTaskId());
        body.put("stepId", request.getStepId());
        body.put("toolId", request.getToolId());
        body.put("toolVersion", request.getToolVersion());
        body.put("input", request.getInput());
        body.put("dryRun", request.getDryRun());
        body.put("idempotencyKey", request.getIdempotencyKey());
        body.put("approvalToken", request.getApprovalToken());
        body.put("clientContext", clientContext(request.getClientContext()));
        return body;
    }

    @SuppressWarnings("unchecked")
    public static ToolExecutionResponse response(Map<String, Object> result) {
        return new ToolExecutionResponse(
                (String) result.get("contractVersion"),
                (String) result.get("executionId"),
                (String) result.get("requestId"),
                (String) result.get("status"),
                (Map<String, Object>) result.get("output"),
                (List<Object>) result.get("content"),
                (List<Object>) result.get("artifacts"),
                (String) result.get("auditRef"),
                (String) result.get("completedAt"));
    }

    private static Map<String, Object> clientContext(ToolClientContextRequest context) {
        if (context == null) {
            return null;
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("locale", context.locale());
        result.put("source", context.source());
        result.put("intentSummary", context.intentSummary());
        return result;
    }

    private ToolExecutionV1Mapper() {
    }
}
