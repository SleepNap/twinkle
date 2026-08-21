package org.gms.httpapi.version;

/** 对外主版本生命周期；RETIRED 保留明确的 410 响应，彻底删除登记后变为 404。 */
public enum ApiVersionStatus {
    ACTIVE,
    DEPRECATED,
    RETIRED
}
