package org.gms.httpapi.api.v1.dto.error;

import java.util.Map;

/** v1 标准错误详情。 */
public record ErrorDetail(String code, String message, boolean retryable,
                          Map<String, Object> details) {
}
