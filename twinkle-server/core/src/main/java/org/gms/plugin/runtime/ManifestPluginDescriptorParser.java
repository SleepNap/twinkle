package org.gms.plugin.runtime;

import lombok.extern.log4j.Log4j2;
import org.gms.plugin.PluginDescriptor;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 插件 manifest 解析（架构 7.3 声明式注册：jar 内 {@code META-INF/twinkle-plugin.properties}）。
 *
 * <p>属性键（对齐 VSCode {@code contributes} 模型）：
 * <pre>
 * plugin.id=com.acme.boss
 * plugin.name=BossPlugin
 * plugin.version=1.2.0
 * plugin.scope=channel            # channel | realm | platform
 * plugin.sdk-version=1
 * plugin.main-class=com.acme.boss.BossPlugin   # 可选
 * contribution.0.type=packet-handler
 * contribution.0.opcode=BOSS_COMMAND
 * contribution.0.class=com.acme.boss.BossCommandHandler
 * contribution.0.version=1
 * </pre>
 * 解析失败 / 字段非法 → {@link PluginDescriptorException}（调用方拒载 + 明确日志，不静默跳过）。
 */
@Log4j2
public final class ManifestPluginDescriptorParser {



    /** manifest 文件名（jar 内路径）。 */
    public static final String MANIFEST_PATH = "META-INF/twinkle-plugin.properties";

    /** 解析失败（manifest 缺失 / 字段非法 / 类型未知）。 */
    public static final class PluginDescriptorException extends RuntimeException {
        public PluginDescriptorException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param jarPath 插件 jar 文件
     * @return 解析出的插件描述
     * @throws PluginDescriptorException manifest 缺失或内容非法
     */
    public PluginDescriptor parse(Path jarPath) throws PluginDescriptorException {
        Properties props = new Properties();
        try (var zf = new java.util.zip.ZipFile(jarPath.toFile())) {
            java.util.zip.ZipEntry entry = zf.getEntry(MANIFEST_PATH);
            if (entry == null) {
                throw new PluginDescriptorException("插件缺少 manifest: " + jarPath
                        + "（jar 内需含 " + MANIFEST_PATH + "）", null);
            }
            try (var in = zf.getInputStream(entry)) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new PluginDescriptorException("读取插件 jar 失败: " + jarPath, e);
        }
        return toDescriptor(jarPath, props);
    }

    private PluginDescriptor toDescriptor(Path jarPath, Properties props) {
        String id = required(props, "plugin.id");
        String name = required(props, "plugin.name");
        String version = required(props, "plugin.version");
        String scopeStr = required(props, "plugin.scope");
        String sdkStr = required(props, "plugin.sdk-version");

        org.gms.plugin.PluginScope scope;
        try {
            scope = org.gms.plugin.PluginScope.valueOf(scopeStr.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PluginDescriptorException("插件 scope 非法: " + scopeStr + "（期望 channel/realm/platform）: " + jarPath, e);
        }
        int sdkVersion;
        try {
            sdkVersion = Integer.parseInt(sdkStr);
        } catch (NumberFormatException e) {
            throw new PluginDescriptorException("插件 sdk-version 非整数: " + sdkStr + "（jar=" + jarPath + "）", e);
        }
        String mainClass = props.getProperty("plugin.main-class", "").trim();

        java.util.List<PluginDescriptor.PacketHandlerContribution> packetHandlers = new java.util.ArrayList<>();
        java.util.List<PluginDescriptor.TickHandlerContribution> tickHandlers = new java.util.ArrayList<>();
        java.util.List<PluginDescriptor.EventListenerContribution> eventListeners = new java.util.ArrayList<>();
        java.util.List<PluginDescriptor.ScriptNamespaceContribution> scriptNamespaces = new java.util.ArrayList<>();
        java.util.List<PluginDescriptor.LogicSystemContribution> logicSystems = new java.util.ArrayList<>();
        java.util.List<PluginDescriptor.AiToolContribution> aiTools = new java.util.ArrayList<>();
        java.util.List<PluginDescriptor.HttpEndpointContribution> httpEndpoints = new java.util.ArrayList<>();

        // 按 contribution.N.type 逐条分桶
        int i = 0;
        while (true) {
            String prefix = "contribution." + i + ".";
            String typeVal = props.getProperty(prefix + "type");
            if (typeVal == null) {
                break; // 序号中断即结束（不要求连续）
            }
            org.gms.plugin.ContributionType type;
            try {
                type = org.gms.plugin.ContributionType.fromCode(typeVal);
            } catch (IllegalArgumentException e) {
                throw new PluginDescriptorException("插件贡献点类型非法: " + typeVal + "（jar=" + jarPath + "）", e);
            }
            // SCRIPT_NAMESPACE 只需 namespace（脚本来自 jar scripts/ 资源），其余类型需 class
            String cls = type == org.gms.plugin.ContributionType.SCRIPT_NAMESPACE
                    ? props.getProperty(prefix + "class", "").trim()
                    : required(props, prefix + "class");
            int cVersion = parseInt(props, prefix + "version", 1);
            switch (type) {
                case PACKET_HANDLER -> packetHandlers.add(new PluginDescriptor.PacketHandlerContribution(
                        required(props, prefix + "opcode"), cls, cVersion));
                case TICK_HANDLER -> tickHandlers.add(new PluginDescriptor.TickHandlerContribution(cls, cVersion));
                case EVENT_LISTENER -> eventListeners.add(new PluginDescriptor.EventListenerContribution(
                        required(props, prefix + "target"),
                        required(props, prefix + "event-class"),
                        cls, cVersion));
                case SCRIPT_NAMESPACE -> scriptNamespaces.add(new PluginDescriptor.ScriptNamespaceContribution(
                        required(props, prefix + "namespace")));
                case LOGIC_SYSTEM -> logicSystems.add(new PluginDescriptor.LogicSystemContribution(
                        required(props, prefix + "key"), cls, cVersion));
                case AI_TOOL -> aiTools.add(new PluginDescriptor.AiToolContribution(cls, cVersion));
                case HTTP_ENDPOINT -> httpEndpoints.add(new PluginDescriptor.HttpEndpointContribution(
                        required(props, prefix + "method"),
                        required(props, prefix + "path"),
                        cls, cVersion));
            }
            i++;
        }

        if (mainClass.isEmpty()) {
            mainClass = null;
        }
        PluginDescriptor descriptor = new PluginDescriptor(
                id, name, version, scope, sdkVersion, mainClass,
                java.util.List.copyOf(packetHandlers), java.util.List.copyOf(tickHandlers),
                java.util.List.copyOf(eventListeners), java.util.List.copyOf(scriptNamespaces),
                java.util.List.copyOf(logicSystems), java.util.List.copyOf(aiTools),
                java.util.List.copyOf(httpEndpoints));
        log.info("解析插件 manifest: {} v{}（scope={}，贡献点 {} 类）", id, version, scope,
                packetHandlers.size() + tickHandlers.size() + eventListeners.size()
                        + scriptNamespaces.size() + logicSystems.size() + aiTools.size() + httpEndpoints.size());
        return descriptor;
    }

    private static String required(Properties props, String key) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new PluginDescriptorException("插件 manifest 缺少必填字段: " + key, null);
        }
        return v.trim();
    }

    private static int parseInt(Properties props, String key, int defaultValue) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new PluginDescriptorException("字段非整数: " + key + "=" + v, e);
        }
    }
}
