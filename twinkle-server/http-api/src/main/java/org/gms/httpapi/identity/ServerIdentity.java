package org.gms.httpapi.identity;

import java.util.Map;
import java.util.Set;

/** 服务端权威身份；不接受 Desktop 或 Tool 输入覆盖。 */
public record ServerIdentity(String serverId, String displayName, String environment, String version) {

    private static final Set<String> ENVIRONMENTS = Set.of(
            "development", "test", "staging", "production");

    public ServerIdentity {
        serverId = requireValue("serverId", serverId, 128);
        displayName = requireValue("displayName", displayName, 128);
        environment = requireValue("environment", environment, 32);
        if (!ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("environment 必须是 development/test/staging/production");
        }
        version = version == null || version.isBlank() ? null : version.trim();
        if (version != null && version.length() > 64) {
            throw new IllegalArgumentException("version 最长 64 字符");
        }
    }

    public Map<String, Object> toSafeMap() {
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("serverId", serverId);
        result.put("displayName", displayName);
        result.put("environment", environment);
        result.put("version", version);
        return result;
    }

    private static String requireValue(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(name + " 必须为 1-" + maxLength + " 个字符");
        }
        return value.trim();
    }
}
