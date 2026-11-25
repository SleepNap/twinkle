package org.gms.database;

import java.util.Locale;
import org.gms.i18n.LogBundle;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 跑在 Environment 就绪之后、所有 bean 创建之前，迁移失败直接让启动失败。
 */
public class DatabaseInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        String url = environment.getProperty("spring.datasource.url");
        if (url == null) {
            throw new IllegalStateException("缺少 spring.datasource.url");
        }
        String username = environment.getProperty("spring.datasource.username");
        String password = environment.getProperty("spring.datasource.password");
        Locale locale = Locale.forLanguageTag(environment.getProperty("twinkle.language", "zh-CN"));
        create(url, username, password, LogBundle.standalone(), locale).migrate();
    }

    private static SchemaMigrator create(
            String url, String username, String password, LogBundle logs, Locale locale) {
        if (url.startsWith("jdbc:sqlite:")) {
            return new SqliteMigrator(url, logs, locale);
        }
        if (url.startsWith("jdbc:postgresql:")) {
            return new PostgresMigrator(url, username, password, logs, locale);
        }
        if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
            return new MySqlMigrator(url, username, password, logs, locale);
        }
        throw new IllegalStateException("不支持的数据库: " + url);
    }
}
