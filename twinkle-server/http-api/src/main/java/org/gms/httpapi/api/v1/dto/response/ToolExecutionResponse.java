package org.gms.httpapi.api.v1.dto.response;

import java.util.List;
import java.util.Map;

/** v1 Tool 同步执行结果；具体 Tool 输出保留为按能力 schema 描述的动态对象。 */
public record ToolExecutionResponse(String contractVersion, String executionId, String requestId,
                                    String status, Map<String, Object> output,
                                    List<Object> content, List<Object> artifacts,
                                    String auditRef, String completedAt) {
}
