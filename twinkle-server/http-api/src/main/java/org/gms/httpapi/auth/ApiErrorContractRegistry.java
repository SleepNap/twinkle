package org.gms.httpapi.auth;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import org.gms.httpapi.api.v1.contract.ApiContract;
import org.gms.httpapi.api.v1.contract.ApiErrorResponses;
import org.gms.httpapi.version.ApiRoutes;

import java.util.Map;

/**
 * 将认证/限流阶段的错误映射到请求主版本自己的冻结错误契约。
 *
 * <p>登记新的公共 API 主版本时必须在此增加显式映射；禁止把未知版本静默按 v1 返回。
 */
public final class ApiErrorContractRegistry {

    public boolean supports(int major) {
        return major == 1;
    }

    public MutableHttpResponse<?> response(
            String path, HttpStatus status, String requestId, String executionId,
            String code, String message, boolean retryable, Map<String, Object> details) {
        return switch (major(path)) {
            case 1 -> ApiErrorResponses.response(status, requestId, executionId, code,
                    message, retryable, details);
            default -> throw new IllegalStateException("No public error contract for " + path);
        };
    }

    public String contractVersion(String path) {
        return switch (major(path)) {
            case 1 -> ApiContract.VERSION;
            default -> throw new IllegalStateException("No public contract version for " + path);
        };
    }

    private static int major(String path) {
        return ApiRoutes.major(ApiRoutes.PUBLIC_ROOT, path);
    }
}
