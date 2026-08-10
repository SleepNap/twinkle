package org.gms.httpapi.execution;

import io.micronaut.http.HttpStatus;

import java.util.Map;

/** 可安全映射到 twish Error Envelope 的预期协议异常。 */
public final class ToolProtocolException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String code;
    private final boolean retryable;
    private final String executionId;
    private final String requestId;
    private final Map<String, Object> details;

    public ToolProtocolException(HttpStatus httpStatus, String code, String message,
                                 boolean retryable, String executionId, String requestId,
                                 Map<String, Object> details) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryable = retryable;
        this.executionId = executionId;
        this.requestId = requestId;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public String executionId() {
        return executionId;
    }

    public String requestId() {
        return requestId;
    }

    public Map<String, Object> details() {
        return details;
    }
}
