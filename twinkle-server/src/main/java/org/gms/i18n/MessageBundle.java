package org.gms.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;

public class MessageBundle {

    private final MessageSource source;

    public MessageBundle(MessageSource source) {
        this.source = source;
    }

    public String get(String key, Locale locale, Object... args) {
        return source.getMessage(key, args, key, locale);
    }
}
