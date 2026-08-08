package org.gms.plugin;

/**
 * PluginApiMarker 模块标记类（M0 占位保留）。
 *
 * <p>存在意义：让架构测试（ArchUnit）能扫描到本模块的类，从而固化"模块边界 + 依赖方向"
 * 规则。M4 起本模块的实际 SPI 类（SdkVersion / Plugin / PluginDescriptor / ContributionType /
 * PluginHost / PluginContext 等）已落地，本类保留作为 ArchUnit 扫描锚点。
 */
public final class PluginApiMarker {

    private PluginApiMarker() {
    }
}
