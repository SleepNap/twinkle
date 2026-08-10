package org.gms.httpapi.contract;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/** 不泄露框架异常和内部路径的稳定错误信封。 */
public final class ApiErrorResponses {

    public static MutableHttpResponse<Map<String, Object>> response(
            HttpStatus status, String requestId, String executionId, String code,
            String message, boolean retryable, Map<String, Object> details) {
        LinkedHashMap<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("retryable", retryable);
        error.put("details", details == null ? Map.of() : details);

        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("contractVersion", ApiContract.VERSION);
        body.put("requestId", requestId);
        body.put("executionId", executionId);
        body.put("error", error);
        return HttpResponse.status(status).body(body);
    }

    private ApiErrorResponses() {
    }
}
