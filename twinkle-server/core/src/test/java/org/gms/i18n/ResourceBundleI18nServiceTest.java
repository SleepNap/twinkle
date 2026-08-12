package org.gms.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceBundleI18nServiceTest {

    @Test
    void allMessagesFollowConfiguredServerLanguage() {
        ResourceBundleI18nService chinese = new ResourceBundleI18nService("zh_CN");
        assertThat(chinese.locale()).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(chinese.message("api.error.rate_limited")).isEqualTo("请求频率过高");

        ResourceBundleI18nService english = new ResourceBundleI18nService("en-US");
        assertThat(english.locale()).isEqualTo(Locale.US);
        assertThat(english.message("api.error.rate_limited")).isEqualTo("Too many requests");
        assertThat(english.message("missing.key")).isEqualTo("missing.key");
    }

    @Test
    void rejectsUnsupportedServerLanguage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ResourceBundleI18nService("fr-FR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fr-FR");
    }
}
