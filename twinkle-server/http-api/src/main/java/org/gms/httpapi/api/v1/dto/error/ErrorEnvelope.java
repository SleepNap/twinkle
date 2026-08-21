package org.gms.httpapi.api.v1.dto.error;

/** v1 标准错误信封。 */
public record ErrorEnvelope(String contractVersion, String requestId, String executionId,
                            ErrorDetail error) {
}
