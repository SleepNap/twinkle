package org.gms.httpapi.version;

/**
 * HTTP 主版本边界。
 *
 * <p>发生变化的端点进入独立版本包；保持兼容的端点可以把同一个方法显式挂载到
 * 多个主版本。无论采用哪种方式，都不得修改已经发布版本的路由与传输语义。
 */
public final class ApiRoutes {

    public static final String PUBLIC_ROOT = "/api";
    public static final String ADMIN_ROOT = "/admin";
    public static final String INTERNAL_ROOT = "/internal";

    // 注解参数要求编译期常量，已发布版本的常量不能改为方法调用。
    public static final String PUBLIC_V1 = PUBLIC_ROOT + "/v1";
    public static final String ADMIN_V1 = ADMIN_ROOT + "/v1";
    public static final String INTERNAL_V1 = INTERNAL_ROOT + "/v1";

    public static String publicVersion(int major) {
        return version(PUBLIC_ROOT, major);
    }

    public static String adminVersion(int major) {
        return version(ADMIN_ROOT, major);
    }

    public static String internalVersion(int major) {
        return version(INTERNAL_ROOT, major);
    }

    /** 去掉平面和主版本前缀；不是合法版本化路径时原样返回。 */
    public static String relativeToVersion(String root, String path) {
        if (path == null || !path.startsWith(root + "/v")) {
            return path;
        }
        int versionStart = root.length() + 2;
        int cursor = versionStart;
        while (cursor < path.length() && Character.isDigit(path.charAt(cursor))) {
            cursor++;
        }
        if (cursor == versionStart || (cursor < path.length() && path.charAt(cursor) != '/')) {
            return path;
        }
        return cursor == path.length() ? "/" : path.substring(cursor);
    }

    /** 从版本化路径读取主版本；路径不合法时返回 -1。 */
    public static int major(String root, String path) {
        if (path == null || !path.startsWith(root + "/v")) {
            return -1;
        }
        int start = root.length() + 2;
        int end = start;
        while (end < path.length() && Character.isDigit(path.charAt(end))) {
            end++;
        }
        if (end == start || (end < path.length() && path.charAt(end) != '/')) {
            return -1;
        }
        try {
            return Integer.parseInt(path.substring(start, end));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String version(String root, int major) {
        if (major < 1) {
            throw new IllegalArgumentException("API major version must be positive");
        }
        return root + "/v" + major;
    }

    private ApiRoutes() {
    }
}
