package org.gms.i18n;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;

public class LogBundle {

    private final MessageSource source;

    public LogBundle(MessageSource source) {
        this.source = source;
    }

    public static LogBundle standalone() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/log");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return new LogBundle(source);
    }

    public String get(String key, Locale locale, Object... args) {
        return source.getMessage(key, args, key, locale);
    }
}
