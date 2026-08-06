package org.gms.plugin;

/**
 * PluginApiMarker 模块标记类（M0 占位）。
 *
 * <p>插件 SPI（贡献点、SDK 版本化）
 *
 * <p>存在意义：让架构测试（ArchUnit）能扫描到本模块的类，从而固化"模块边界 + 依赖方向"
 * 规则。M1 起该模块的实际类替换此占位。
 */
public final class PluginApiMarker {

    private PluginApiMarker() {
    }
}
