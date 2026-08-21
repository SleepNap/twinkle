package org.gms.data;

import io.micronaut.context.ApplicationContext;
import org.gms.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceI18nStartupTest {

    @AfterEach
    void resetFacade() {
        I18n.install(null);
    }

    @Test
    void eagerDataSourceInstallsConfiguredI18nBeforeStartupLogs() throws Exception {
        I18n.install(null);
        String dbPath = Files.createTempDirectory("twinkle-i18n-startup")
                .resolve("test.db")
                .toString();

        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.service.language", "en-US"))) {
            assertThat(context.getBean(DataSource.class)).isNotNull();
            assertThat(I18n.locale()).isEqualTo(Locale.US);
            assertThat(I18n.message("log.migrate.applied"))
                    .isEqualTo("Applying migration V{}: {}");
        }
    }
}
