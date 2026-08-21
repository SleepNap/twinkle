package org.gms.httpapi.version;

import java.time.Instant;

/** 一个网络平面上的主版本发布状态。 */
public record ApiVersionDefinition(
        ApiPlane plane,
        int major,
        ApiVersionStatus status,
        Instant sunsetAt,
        String migrationGuide
) {
    public ApiVersionDefinition {
        if (major < 1) {
            throw new IllegalArgumentException("API major version must be positive");
        }
        migrationGuide = migrationGuide == null ? "" : migrationGuide;
    }
}
