package org.gms.plugin.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.gms.plugin.ContributionType;
import org.gms.plugin.PluginDescriptor;
import org.gms.plugin.PluginScope;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Manifest 插件描述解析验证（架构 7.3 声明式注册：manifest 驱动）。
 */
class ManifestPluginDescriptorParserTest {

    private static final String MANIFEST = """
            plugin.id=com.acme.boss
            plugin.name=BossPlugin
            plugin.version=1.2.0
            plugin.scope=channel
            plugin.sdk-version=1
            plugin.main-class=com.acme.boss.BossPlugin

            contribution.0.type=packet-handler
            contribution.0.opcode=BOSS_COMMAND
            contribution.0.class=com.acme.boss.BossCommandHandler
            contribution.0.version=1

            contribution.1.type=tick-handler
            contribution.1.class=com.acme.boss.BossRespawnTask
            contribution.1.version=3

            contribution.2.type=event-listener
            contribution.2.target=online-player-events
            contribution.2.event-class=org.gms.service.admin.OnlinePlayerEvents$PlayerOnline
            contribution.2.class=com.acme.boss.BossEventListener
            contribution.2.version=1

            contribution.3.type=script-namespace
            contribution.3.namespace=acme

            contribution.4.type=logic-system
            contribution.4.key=combat
            contribution.4.class=com.acme.boss.BossCombatSystem
            contribution.4.version=1
            """;

    @TempDir
    Path tmp;

    @Test
    void parsesAllContributionTypes() throws Exception {
        Path jar = TestPluginJars.writeManifestOnlyJar(tmp, "com.acme.boss.jar", MANIFEST);
        PluginDescriptor d = new ManifestPluginDescriptorParser().parse(jar);

        assertThat(d.id()).isEqualTo("com.acme.boss");
        assertThat(d.name()).isEqualTo("BossPlugin");
        assertThat(d.version()).isEqualTo("1.2.0");
        assertThat(d.scope()).isEqualTo(PluginScope.CHANNEL);
        assertThat(d.sdkVersion()).isEqualTo(1);
        assertThat(d.mainClass()).isEqualTo("com.acme.boss.BossPlugin");

        assertThat(d.packetHandlers()).hasSize(1);
        assertThat(d.packetHandlers().get(0).opcode()).isEqualTo("BOSS_COMMAND");
        assertThat(d.packetHandlers().get(0).className()).isEqualTo("com.acme.boss.BossCommandHandler");
        assertThat(d.packetHandlers().get(0).version()).isEqualTo(1);

        assertThat(d.tickHandlers()).hasSize(1);
        assertThat(d.tickHandlers().get(0).version()).isEqualTo(3); // 显式版本

        assertThat(d.eventListeners()).hasSize(1);
        assertThat(d.eventListeners().get(0).target()).isEqualTo("online-player-events");
        assertThat(d.eventListeners().get(0).eventClassName())
                .isEqualTo("org.gms.service.admin.OnlinePlayerEvents$PlayerOnline");

        assertThat(d.scriptNamespaces()).hasSize(1);
        assertThat(d.scriptNamespaces().get(0).namespace()).isEqualTo("acme");

        assertThat(d.logicSystems()).hasSize(1);
        assertThat(d.logicSystems().get(0).key()).isEqualTo("combat");
    }

    @Test
    void rejectsMissingManifest() throws Exception {
        Path jar = tmp.resolve("empty.jar");
        java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                java.nio.file.Files.newOutputStream(jar));
        jos.close();

        assertThatThrownBy(() -> new ManifestPluginDescriptorParser().parse(jar))
                .isInstanceOf(ManifestPluginDescriptorParser.PluginDescriptorException.class)
                .hasMessageContaining("缺少 manifest");
    }

    @Test
    void rejectsMissingRequiredField() throws Exception {
        Path jar = TestPluginJars.writeManifestOnlyJar(tmp, "bad.jar", "plugin.name=NoId\n");
        assertThatThrownBy(() -> new ManifestPluginDescriptorParser().parse(jar))
                .isInstanceOf(ManifestPluginDescriptorParser.PluginDescriptorException.class)
                .hasMessageContaining("plugin.id");
    }

    @Test
    void rejectsUnknownContributionType() throws Exception {
        String manifest = """
                plugin.id=com.acme.bad
                plugin.name=Bad
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                contribution.0.type=unknown-type
                contribution.0.class=com.acme.bad.X
                """;
        Path jar = TestPluginJars.writeManifestOnlyJar(tmp, "bad.jar", manifest);
        assertThatThrownBy(() -> new ManifestPluginDescriptorParser().parse(jar))
                .isInstanceOf(ManifestPluginDescriptorParser.PluginDescriptorException.class)
                .hasMessageContaining("贡献点类型非法");
    }

    @Test
    void contributionTypeCodesMatchManifest() {
        assertThat(ContributionType.PACKET_HANDLER.code()).isEqualTo("packet-handler");
        assertThat(ContributionType.fromCode("script-namespace")).isEqualTo(ContributionType.SCRIPT_NAMESPACE);
    }
}
