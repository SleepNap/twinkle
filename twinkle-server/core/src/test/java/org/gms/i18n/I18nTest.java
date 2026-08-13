package org.gms.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class I18nTest {

    @Test
    void messageUsesInstalledDelegate() {
        I18n.install(new ResourceBundleI18nService("en-US"));
        assertThat(I18n.message("api.error.rate_limited")).isEqualTo("Too many requests");
        assertThat(I18n.locale()).isEqualTo(Locale.US);
    }

    @Test
    void messageFallsBackToKeyWhenNotInstalled() {
        I18n.install(null);
        assertThat(I18n.message("any.key")).isEqualTo("any.key");
        assertThat(I18n.locale()).isEqualTo(Locale.ROOT);
    }

    @Test
    void messageInterpolatesArguments() {
        I18n.install(new ResourceBundleI18nService("zh-CN"));
        assertThat(I18n.message("error.plugin.jar_not_found", "com.acme", "/plugins")).contains("com.acme");
    }
}
