package org.gms.httpapi.api.v1.dto.response;

import java.util.List;

/** v1 Tool 能力目录。 */
public record CapabilityCatalogResponse(String contractVersion, String catalogVersion,
                                        String permissionVersion, List<ToolSummaryResponse> tools,
                                        String generatedAt) {
}
