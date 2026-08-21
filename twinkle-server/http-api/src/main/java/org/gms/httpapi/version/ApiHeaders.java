package org.gms.httpapi.version;

/** 跨主版本保持稳定的 HTTP Header 名称。 */
public final class ApiHeaders {

    public static final String CONTRACT_VERSION = "X-Contract-Version";
    public static final String REQUEST_ID = "X-Request-Id";

    private ApiHeaders() {
    }
}
