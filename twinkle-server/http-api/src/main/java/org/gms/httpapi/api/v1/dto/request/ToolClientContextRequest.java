package org.gms.httpapi.api.v1.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** v1 Tool 调用方上下文。 */
public record ToolClientContextRequest(
        @Schema(maxLength = 32) String locale,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "调用来源：desktop、web、plugin、local_im 或 server_im")
        String source,
        @Schema(maxLength = 512) String intentSummary) {
}
