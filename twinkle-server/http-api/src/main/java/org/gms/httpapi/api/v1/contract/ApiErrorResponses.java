package org.gms.httpapi.api.v1.contract;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import org.gms.httpapi.api.v1.dto.error.ErrorDetail;
import org.gms.httpapi.api.v1.dto.error.ErrorEnvelope;

import java.util.Map;

/** 构造不泄露框架异常和内部路径的 v1 稳定错误信封。 */
public final class ApiErrorResponses {

    public static MutableHttpResponse<ErrorEnvelope> response(
            HttpStatus status, String requestId, String executionId, String code,
            String message, boolean retryable, Map<String, Object> details) {
        ErrorDetail error = new ErrorDetail(code, message, retryable,
                details == null ? Map.of() : details);
        return HttpResponse.status(status).body(new ErrorEnvelope(
                ApiContract.VERSION, requestId, executionId, error));
    }

    private ApiErrorResponses() {
    }
}
