package org.gms.httpapi.identity;

import org.gms.i18n.I18n;

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
            throw new IllegalArgumentException(I18n.message("error.identity.invalid_environment"));
        }
        version = version == null || version.isBlank() ? null : version.trim();
        if (version != null && version.length() > 64) {
            throw new IllegalArgumentException(I18n.message("error.identity.version_too_long"));
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
            throw new IllegalArgumentException(I18n.message("error.identity.field_length", name, maxLength));
        }
        return value.trim();
    }
}
