package org.gms.httpapi.billing;

/** 积分计费异常。code 用于映射 HTTP 429 响应的 error 码。 */
public final class BillingException extends RuntimeException {

    private final String code;

    public BillingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
