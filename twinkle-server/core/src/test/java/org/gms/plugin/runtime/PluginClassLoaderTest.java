package org.gms.plugin.runtime;

import org.gms.config.ConfigChangeEvent;
import org.gms.event.EventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 插件 classloader 隔离测试（架构 7.3 可见性边界：绝不能加载/遮蔽稳定层类，否则 CCE）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>{@code org.gms.*} 一律父优先 → 插件 jar 即使塞了同名类也加载宿主真身（结构上不可遮蔽）。</li>
 *   <li>插件自有类（非 {@code org.gms} 包，如 {@code com.acme.*}）本地优先，换 loader = 新 Class。</li>
 * </ul>
 *
 * <p>宿主类样本用 core 可见的稳定层类（core 不依赖 data，故不用 data.entity 做样本）。
 */
class PluginClassLoaderTest {

    @TempDir
    Path tmp;

    /** 宿主类（稳定层 + SDK + 任意 org.gms）由父加载，插件 loader 绝不本地加载。 */
    @Test
    void hostClassesAlwaysLoadedByParent() throws Exception {
        ClassLoader parent = PluginClassLoaderTest.class.getClassLoader();
        try (PluginClassLoader loader = new PluginClassLoader("test", new URL[0], parent)) {
            assertThat(loader.loadClass(ConfigChangeEvent.class.getName()).getClassLoader()).isEqualTo(parent);
            assertThat(loader.loadClass(EventBus.class.getName()).getClassLoader()).isEqualTo(parent);
            assertThat(loader.loadClass(org.gms.plugin.SdkVersion.class.getName()).getClassLoader()).isEqualTo(parent);
        }
    }

    /**
     * 防遮蔽核心：插件 jar 内塞一个假的 {@code org.gms.config.ConfigChangeEvent}（与宿主同名同包），
     * 加载时必须命中宿主真身（父优先），绝不能被插件本地类遮蔽。
     */
    @Test
    void shadowingFakeHostClassIsImpossible() throws Exception {
        String manifest = """
                plugin.id=com.acme.shadow
                plugin.name=Shadow
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                """;
        String fake = """
                package org.gms.config;
                public class ConfigChangeEvent {
                    public String fake() { return "fake"; }
                }
                """;
        Path jar = TestPluginJars.writePluginJarWithClasses(tmp, "com.acme.shadow.jar", manifest,
                Map.of("org.gms.config.ConfigChangeEvent", fake));

        ClassLoader parent = PluginClassLoaderTest.class.getClassLoader();
        try (PluginClassLoader loader = new PluginClassLoader("shadow", new URL[]{jar.toUri().toURL()}, parent)) {
            Class<?> loaded = loader.loadClass(ConfigChangeEvent.class.getName());
            // 宿主真身：父加载器加载，与本测试所在类加载器相同
            assertThat(loaded.getClassLoader()).isEqualTo(parent);
            // 真身没有 fake() 方法（若被遮蔽则会有）
            assertThat(loaded.getDeclaredMethods())
                    .noneMatch(m -> m.getName().equals("fake"));
        }
    }

    /** 插件自有类（非 org.gms 包）本地优先，换 loader = 新 Class。 */
    @Test
    void pluginOwnClassesLoadedLocallyAndDistinctAcrossLoaders() throws Exception {
        String manifest = """
                plugin.id=com.acme.own
                plugin.name=Own
                plugin.version=1.0.0
                plugin.scope=channel
                plugin.sdk-version=1
                """;
        String ownClass = """
                package com.acme.own;
                public class PluginClass {
                    public String hello() { return "hi"; }
                }
                """;
        Path jar = TestPluginJars.writePluginJarWithClasses(tmp, "com.acme.own.jar", manifest,
                Map.of("com.acme.own.PluginClass", ownClass));

        ClassLoader parent = PluginClassLoaderTest.class.getClassLoader();
        try (PluginClassLoader loaderA = new PluginClassLoader("own-a", new URL[]{jar.toUri().toURL()}, parent);
             PluginClassLoader loaderB = new PluginClassLoader("own-b", new URL[]{jar.toUri().toURL()}, parent)) {

            Class<?> a = loaderA.loadClass("com.acme.own.PluginClass");
            Class<?> b = loaderB.loadClass("com.acme.own.PluginClass");
            assertThat(a.getClassLoader()).isInstanceOf(PluginClassLoader.class);
            assertThat(a).isNotEqualTo(b); // 换 loader = 新 Class（L3 模块替换同构）
        }
    }

    /** 插件 jar 内确实存在（Files.exists），验证打包生效。 */
    @Test
    void pluginJarWritten() throws Exception {
        String manifest = "plugin.id=x\nplugin.name=X\nplugin.version=1\nplugin.scope=channel\nplugin.sdk-version=1\n";
        Path jar = TestPluginJars.writeManifestOnlyJar(tmp, "x.jar", manifest);
        assertThat(Files.exists(jar)).isTrue();
    }
}
