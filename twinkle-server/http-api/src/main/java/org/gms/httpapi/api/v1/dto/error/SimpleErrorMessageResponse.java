package org.gms.httpapi.api.v1.dto.error;

/** 早期 v1 接口冻结的带消息简化错误响应。 */
public record SimpleErrorMessageResponse(String error, String message) {
}
