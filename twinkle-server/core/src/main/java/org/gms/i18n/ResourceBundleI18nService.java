package org.gms.i18n;

import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;

import java.text.MessageFormat;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/** 基于 UTF-8 properties ResourceBundle 的轻量全局国际化实现。 */
@Singleton
public final class ResourceBundleI18nService implements I18nService {

    static final String BUNDLE_NAME = "org.gms.i18n.messages";
    private static final ResourceBundle.Control NO_SYSTEM_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private final Locale locale;

    public ResourceBundleI18nService(
            @Property(name = "twinkle.service.language", defaultValue = "zh-CN") String languageTag) {
        Locale configured = parseLocale(languageTag);
        if (!configured.equals(Locale.SIMPLIFIED_CHINESE) && !configured.equals(Locale.US)) {
            throw new IllegalArgumentException("不支持的服务端语言: " + languageTag
                    + "（当前支持 zh-CN、en-US）");
        }
        this.locale = configured;
    }

    @Override
    public Locale locale() {
        return locale;
    }

    @Override
    public String message(String key, Object... arguments) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("message key 不能为空");
        }
        String pattern = find(key, locale);
        if (pattern == null) {
            return key;
        }
        return arguments == null || arguments.length == 0
                ? pattern
                : new MessageFormat(pattern, locale).format(arguments);
    }

    private static String find(String key, Locale locale) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(
                    BUNDLE_NAME, locale, ResourceBundleI18nService.class.getClassLoader(), NO_SYSTEM_FALLBACK);
            return bundle.containsKey(key) ? bundle.getString(key) : null;
        } catch (MissingResourceException ignored) {
            return null;
        }
    }

    private static Locale parseLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        try {
            Locale locale = new Locale.Builder().setLanguageTag(raw.trim().replace('_', '-')).build();
            return locale.getLanguage().isBlank() ? Locale.SIMPLIFIED_CHINESE : locale;
        } catch (IllformedLocaleException ignored) {
            throw new IllegalArgumentException("非法的服务端语言标签: " + raw);
        }
    }
}
