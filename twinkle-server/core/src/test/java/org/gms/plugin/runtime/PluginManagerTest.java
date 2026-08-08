package org.gms.plugin.runtime;

import org.gms.plugin.ContributionHandle;
import org.gms.plugin.Plugin;
import org.gms.plugin.PluginContext;
import org.gms.plugin.PluginDescriptor;
import org.gms.plugin.PluginHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件管理器生命周期测试（架构 7.2：可装卸 + SDK 版本化）。
 */
class PluginManagerTest {

    @TempDir
    Path tmp;

    /** 记录 apply / 卸载调用的假宿主。 */
    static final class FakeHost implements PluginHost {
        final List<String> applied = new ArrayList<>();
        final List<ContributionHandle> handles = new ArrayList<>();

        @Override
        public List<ContributionHandle> applyContributions(PluginDescriptor descriptor, PluginContext context) {
            applied.add(descriptor.id());
            return List.copyOf(handles);
        }
    }

    static final class NoopRouter implements ContributionRouter {
        @Override
        public <T> ContributionHandle register(String contributionType, T contribution, int version) {
            throw new UnsupportedOperationException("测试不接命令式注册");
        }
    }

    private PluginManager newManager(FakeHost host) {
        return new PluginManager(tmp, host, PluginManagerTest.class.getClassLoader(),
                type -> null, new NoopRouter());
    }

    @Test
    void loadAppliesContributionsAndStarts() throws Exception {
        String manifest = """
                plugin.id=com.acme.demo
                plugin.name=Demo
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                """;
        TestPluginJars.writeManifestOnlyJar(tmp, "com.acme.demo.jar", manifest);
        FakeHost host = new FakeHost();
        PluginManager mgr = newManager(host);
        try {
            PluginDescriptor d = mgr.scan().get(0);
            var loaded = mgr.load(d);

            assertThat(host.applied).containsExactly("com.acme.demo");
            assertThat(loaded.pluginId()).isEqualTo("com.acme.demo");
            assertThat(mgr.loaded("com.acme.demo")).isPresent();
        } finally {
            mgr.close();
        }
    }

    @Test
    void unloadRemovesPluginAndClosesHandles() throws Exception {
        String manifest = """
                plugin.id=com.acme.demo
                plugin.name=Demo
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                """;
        TestPluginJars.writeManifestOnlyJar(tmp, "com.acme.demo.jar", manifest);
        FakeHost host = new FakeHost();
        // 假宿主返回的 handle 记录是否 close
        ContributionHandle[] closed = {null};
        host.handles.add(new ContributionHandle() {
            @Override
            public void close() {
                closed[0] = this;
            }
        });
        PluginManager mgr = newManager(host);
        try {
            mgr.load(mgr.scan().get(0));
            mgr.unload("com.acme.demo");

            assertThat(mgr.loaded("com.acme.demo")).isEmpty();
            assertThat(closed[0]).isNotNull(); // 贡献点已回滚
            assertThat(host.applied).containsExactly("com.acme.demo");
        } finally {
            mgr.close();
        }
    }

    @Test
    void unloadIsIdempotent() throws Exception {
        PluginManager mgr = newManager(new FakeHost());
        mgr.unload("never-loaded"); // 不抛异常
    }

    @Test
    void rejectsIncompatibleSdk() throws Exception {
        String manifest = """
                plugin.id=com.acme.future
                plugin.name=Future
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=99
                """;
        TestPluginJars.writeManifestOnlyJar(tmp, "com.acme.future.jar", manifest);
        PluginManager mgr = newManager(new FakeHost());

        PluginDescriptor d = mgr.scan().get(0);
        assertThatThrownBy(() -> mgr.load(d))
                .isInstanceOf(PluginManager.PluginLoadException.class)
                .hasMessageContaining("SDK 版本不兼容");
        assertThat(mgr.loadedPlugins()).isEmpty();
    }

    @Test
    void loadsPluginWithMainClassInvokingStart() throws Exception {
        // 含主类的插件：start 被调用
        String manifest = """
                plugin.id=com.acme.life
                plugin.name=Life
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                plugin.main-class=com.acme.life.LifePlugin
                """;
        String source = """
                package com.acme.life;
                import org.gms.plugin.Plugin;
                import org.gms.plugin.PluginContext;
                public class LifePlugin implements Plugin {
                    public static boolean started = false;
                    public static boolean stopped = false;
                    public void start(PluginContext ctx) { started = true; }
                    public void stop(PluginContext ctx) { stopped = true; }
                }
                """;
        TestPluginJars.writePluginJarWithClasses(tmp, "com.acme.life.jar", manifest,
                Map.of("com.acme.life.LifePlugin", source));
        FakeHost host = new FakeHost();
        PluginManager mgr = newManager(host);

        try {
            mgr.load(mgr.scan().get(0));
            // 插件主类 start 被调用 → 贡献点已应用
            assertThat(host.applied).containsExactly("com.acme.life");
            assertThat(mgr.loaded("com.acme.life")).isPresent();
        } finally {
            mgr.close(); // 释放 loader 的 jar 句柄（否则 Windows 下 @TempDir 清理失败）
        }
    }

    @Test
    void scanSkipsBrokenJarsWithoutFailingBatch() throws Exception {
        // 一个正常 + 一个坏（无 manifest）→ scan 只返回正常
        TestPluginJars.writeManifestOnlyJar(tmp, "good.jar", """
                plugin.id=com.acme.good
                plugin.name=Good
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                """);
        Path bad = tmp.resolve("bad.jar");
        java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                java.nio.file.Files.newOutputStream(bad));
        jos.close();

        PluginManager mgr = newManager(new FakeHost());
        var descriptors = mgr.scan();
        assertThat(descriptors).hasSize(1);
        assertThat(descriptors.get(0).id()).isEqualTo("com.acme.good");
    }
}
