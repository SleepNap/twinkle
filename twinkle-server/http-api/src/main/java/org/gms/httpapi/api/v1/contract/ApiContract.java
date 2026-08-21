package org.gms.httpapi.api.v1.contract;

import org.gms.httpapi.version.ApiHeaders;

/** 公共 API v1 契约常量。 */
public final class ApiContract {

    public static final String VERSION = "0.1";
    public static final String CONTRACT_HEADER = ApiHeaders.CONTRACT_VERSION;
    public static final String REQUEST_ID_HEADER = ApiHeaders.REQUEST_ID;

    private ApiContract() {
    }
}
