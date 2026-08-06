package org.gms.hotreload;

import org.gms.config.ConfigChangeEvent;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可重载模块 classloader 测试（架构 5.1：可重载逻辑隔离，状态不进该 classloader）。
 *
 * <p>验证核心语义：稳定层父优先（同类身份不变）、可替换层本地优先、替换后无旧类冲突。
 */
class ReloadableClassLoaderTest {

    /**
     * 稳定层类必须由父 classloader 加载（永不在此 loader 重复加载）→ 换 loader 后类身份稳定。
     */
    @Test
    void stableClassesAreLoadedByParent() throws Exception {
        try (ReloadableClassLoader loader = new ReloadableClassLoader(
                "test-module", new URL[0], ReloadableClassLoaderTest.class.getClassLoader())) {

            Class<?> configEvent = loader.loadClass(ConfigChangeEvent.class.getName());
            assertThat(configEvent.getClassLoader()).isEqualTo(
                    ReloadableClassLoaderTest.class.getClassLoader());
            // 父加载：ConfigChangeEvent 是稳定层（org.gms.config..）
            assertThat(configEvent.getClassLoader())
                    .isNotInstanceOf(ReloadableClassLoader.class);
        }
    }

    /**
     * isStableClass 正确识别稳定层包前缀（与 ArchUnit 规则共用语义）。
     */
    @Test
    void isStableClass_detectsStablePackages() {
        assertThat(ReloadableClassLoader.isStableClass("org.gms.data.entity.Character")).isTrue();
        assertThat(ReloadableClassLoader.isStableClass("org.gms.dialect.DbDialect")).isTrue();
        assertThat(ReloadableClassLoader.isStableClass("org.gms.event.EventBus")).isTrue();
        assertThat(ReloadableClassLoader.isStableClass("org.gms.config.ConfigFacade")).isTrue();
        assertThat(ReloadableClassLoader.isStableClass("org.gms.replaceable.SomeSystem")).isFalse();
    }

    /**
     * 同一模块名创建两个 loader，加载同一可替换层类得到两个不同 Class 实例（旧类不可见冲突）。
     * M4 接入插件后，替换 = 新 loader + 新实例 + 重新绑定接口。
     */
    @Test
    void twoLoadersProduceDistinctClassesForReplaceableLayer() throws Exception {
        // 用一个临时目录放"可替换层"测试类：把当前 test 类拷进一个 jar 的替代——这里用
        // 一个能实际加载的类。用本类的类路径目录作为 URL。
        URL classesDir = ReloadableClassLoaderTest.class.getProtectionDomain().getCodeSource().getLocation();

        try (ReloadableClassLoader loaderA = new ReloadableClassLoader("mod-v1", new URL[]{classesDir}, getParent());
             ReloadableClassLoader loaderB = new ReloadableClassLoader("mod-v2", new URL[]{classesDir}, getParent())) {

            // 可替换层类（不在稳定层前缀内）→ 本 loader 加载 → 两个 loader 两个 Class
            Class<?> a = loaderA.loadClass(ReloadableClassLoaderTest.class.getName());
            Class<?> b = loaderB.loadClass(ReloadableClassLoaderTest.class.getName());
            assertThat(a).isNotEqualTo(b);
            assertThat(a.getClassLoader()).isInstanceOf(ReloadableClassLoader.class);
            assertThat(b.getClassLoader()).isInstanceOf(ReloadableClassLoader.class);
        }
    }

    /**
     * dispose 关闭 jar 句柄（资源释放）。
     */
    @Test
    void disposeClosesCleanly() throws MalformedURLException {
        ReloadableClassLoader loader = new ReloadableClassLoader(
                "test", new URL[0], ReloadableClassLoaderTest.class.getClassLoader());
        loader.dispose(); // 不应抛异常
        assertThat(loader).isNotNull();
    }

    private static ClassLoader getParent() {
        return ReloadableClassLoaderTest.class.getClassLoader();
    }
}
