package org.gms.hotreload;

import org.gms.i18n.I18n;
import org.gms.i18n.ResourceBundleI18nService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逻辑系统注册表版本化验证（贡献点版本化范式，红线 13）。
 */
class LogicSystemRegistryTest {

    private LogicSystemRegistry registry;

    @BeforeEach
    void setUp() {
        I18n.install(new ResourceBundleI18nService("zh-CN"));
        registry = new LogicSystemRegistry();
    }

    @Test
    void registerThenFind() {
        Object v1 = new Object();
        registry.register("combat", v1);
        assertThat(registry.<Object>find("combat")).contains(v1);
        assertThat(registry.registeredCount()).isEqualTo(1);
    }

    @Test
    void duplicateRegisterRejected() {
        registry.register("combat", new Object());
        assertThatThrownBy(() -> registry.register("combat", new Object()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已注册");
    }

    @Test
    void replaceWithHigherVersionWins() {
        Object v1 = new Object();
        Object v2 = new Object();
        registry.register("combat", v1, 1);
        registry.replace("combat", v2, 2);
        assertThat(registry.<Object>find("combat")).contains(v2);
    }

    @Test
    void replaceWithLowerVersionRejected() {
        registry.register("combat", new Object(), 2);
        assertThatThrownBy(() -> registry.replace("combat", new Object(), 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("须高于");
    }

    @Test
    void unregisterRemovesSlot() {
        registry.register("combat", new Object());
        assertThat(registry.unregister("combat")).isTrue();
        assertThat(registry.<Object>find("combat")).isEmpty();
        assertThat(registry.unregister("combat")).isFalse(); // 幂等
    }
}
