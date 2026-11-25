package org.gms.config;

import java.util.Locale;
import org.gms.constant.ProcessRole;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("twinkle")
public record TwinkleProperties(
        @DefaultValue("twinkle") String name,
        ProcessRole role,
        String language) {

    public Locale locale() {
        return Locale.forLanguageTag(language);
    }
}
