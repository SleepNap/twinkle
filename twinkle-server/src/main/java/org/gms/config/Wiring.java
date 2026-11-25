package org.gms.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.gms.i18n.LogBundle;
import org.gms.i18n.MessageBundle;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.io.ClassPathResource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TwinkleProperties.class)
public class Wiring {

    @Bean
    public MessageBundle messageBundle() {
        return new MessageBundle(bundle("i18n/message"));
    }

    @Bean
    public LogBundle logBundle() {
        return LogBundle.standalone();
    }

    @Bean
    public BuildProperties buildProperties() {
        Properties props = new Properties();
        try (InputStream in = new ClassPathResource("build-info.properties").getInputStream()) {
            props.load(in);
        } catch (IOException ignored) {
            // 读不到就当没有版本号，不影响启动
        }
        return new BuildProperties(props);
    }

    private static ResourceBundleMessageSource bundle(String basename) {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(basename);
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
