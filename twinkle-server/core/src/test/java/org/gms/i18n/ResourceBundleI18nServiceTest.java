package org.gms.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        assertThatThrownBy(() -> new ResourceBundleI18nService("fr-FR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fr-FR");
    }

    @Test
    void logTemplateKeepsSlf4jPlaceholderWhenNoArgs() {
        ResourceBundleI18nService service = new ResourceBundleI18nService("zh-CN");
        // 日志 key：无参 message() 返回原始 {} 模板，参数交给 log4j 填充
        assertThat(service.message("log.tick.started"))
                .isEqualTo("【任务调度】游戏周期调度器已启动，基础周期 {} ms");
    }

    @Test
    void exceptionMessageInterpolatesWithMessageFormat() {
        ResourceBundleI18nService service = new ResourceBundleI18nService("zh-CN");
        assertThat(service.message("error.plugin.jar_not_found", "com.acme", "/plugins"))
                .isEqualTo("插件 jar 未找到: com.acme（目录=/plugins）");
    }

    @Test
    void zhCnAndEnUsHaveMatchingKeys() throws IOException {
        Set<String> zh = loadKeys("messages_zh_CN.properties");
        Set<String> en = loadKeys("messages_en_US.properties");
        assertThat(en).isEqualTo(zh);
    }

    private static Set<String> loadKeys(String resourceName) throws IOException {
        Properties props = new Properties();
        try (InputStream in = ResourceBundleI18nServiceTest.class.getClassLoader()
                .getResourceAsStream("org/gms/i18n/" + resourceName)) {
            props.load(in);
        }
        return props.stringPropertyNames();
    }
}
