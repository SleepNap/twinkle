package org.gms.httpapi.api.v1.dto.response;

import java.util.List;

/** v1 Tool 目录中的轻量条目。 */
public record ToolSummaryResponse(String toolId, String toolVersion, String title, String summary,
                                  String provider, List<String> categories, List<String> tags,
                                  String riskLevel, String availability, String permissionState) {
}
