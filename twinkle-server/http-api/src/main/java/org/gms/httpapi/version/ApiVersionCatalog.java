package org.gms.httpapi.version;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已发布 HTTP 主版本的唯一登记表。
 *
 * <p>新增 Controller 并不自动发布版本：必须先在这里登记，过滤器才会放行。
 * 下线时先标记 DEPRECATED/RETIRED，最后随对应 Controller 与契约一起删除登记。
 */
public final class ApiVersionCatalog {

    private final Map<ApiPlane, Map<Integer, ApiVersionDefinition>> versions;

    public ApiVersionCatalog() {
        EnumMap<ApiPlane, Map<Integer, ApiVersionDefinition>> initial = new EnumMap<>(ApiPlane.class);
        for (ApiPlane plane : ApiPlane.values()) {
            initial.put(plane, Map.of(1, new ApiVersionDefinition(
                    plane, 1, ApiVersionStatus.ACTIVE, null, "")));
        }
        versions = Map.copyOf(initial);
    }

    public Optional<ApiVersionDefinition> find(ApiPlane plane, int major) {
        return Optional.ofNullable(versions.getOrDefault(plane, Map.of()).get(major));
    }

    public List<ApiVersionDefinition> definitions(ApiPlane plane) {
        return versions.getOrDefault(plane, Map.of()).values().stream()
                .sorted(java.util.Comparator.comparingInt(ApiVersionDefinition::major))
                .toList();
    }

    public Optional<ResolvedVersion> resolve(String path) {
        for (ApiPlane plane : ApiPlane.values()) {
            int major = ApiRoutes.major(plane.root(), path);
            if (major > 0) {
                return Optional.of(new ResolvedVersion(plane, major, find(plane, major).orElse(null)));
            }
        }
        return Optional.empty();
    }

    public record ResolvedVersion(ApiPlane plane, int major, ApiVersionDefinition definition) {
        public boolean registered() {
            return definition != null;
        }
    }
}
