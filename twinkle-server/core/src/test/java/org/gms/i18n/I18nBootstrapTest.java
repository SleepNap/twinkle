package org.gms.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class I18nBootstrapTest {

    @AfterEach
    void resetFacade() {
        I18n.install(null);
    }

    @Test
    void installsConfiguredServiceIntoStaticFacade() {
        I18n.install(null);

        new I18nBootstrap(new ResourceBundleI18nService("en-US"));

        assertThat(I18n.locale()).isEqualTo(Locale.US);
        assertThat(I18n.message("log.data.init"))
                .isEqualTo("[Database] Connecting data source: {}");
    }
}
