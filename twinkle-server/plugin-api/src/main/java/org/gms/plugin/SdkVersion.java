package org.gms.plugin;

/**
 * 插件 SDK 版本化（架构 7.1 SDK 版本化 / 红线 13：贡献点从第一天版本化）。
 *
 * <p>插件 manifest 声明 {@code plugin.sdk-version}，加载时校验 ∈ [MIN_COMPATIBLE, CURRENT]，
 * 不兼容即拒载（fail fast + 明确日志）。SDK 出现不兼容变更（删方法 / 改签名）时 CURRENT +1。
 */
public final class SdkVersion {

    /** 当前 SDK 版本。不兼容变更时 +1。 */
    public static final int CURRENT = 1;

    /** 兼容下限（向后兼容的最低版本）。 */
    public static final int MIN_COMPATIBLE = 1;

    private SdkVersion() {
    }
}
