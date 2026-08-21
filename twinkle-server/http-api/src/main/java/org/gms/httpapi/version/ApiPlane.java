package org.gms.httpapi.version;

/** HTTP API 的三个独立网络平面。 */
public enum ApiPlane {
    PUBLIC(ApiRoutes.PUBLIC_ROOT),
    ADMIN(ApiRoutes.ADMIN_ROOT),
    INTERNAL(ApiRoutes.INTERNAL_ROOT);

    private final String root;

    ApiPlane(String root) {
        this.root = root;
    }

    public String root() {
        return root;
    }
}
