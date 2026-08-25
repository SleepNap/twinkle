package org.gms.bootstrap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingConfigurationTest {

    @Test
    void mapperSqlIsQuietByDefaultAndCanBeEnabledByEnvironment() throws Exception {
        try (var input = getClass().getResourceAsStream("/log4j2.xml")) {
            assertThat(input).isNotNull();
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(xml).contains("TWINKLE_SQL_LOG_LEVEL:-INFO");
            assertThat(xml).contains("name=\"org.gms.data.mapper\"");
            assertThat(xml).contains("level=\"${SQL_LOG_LEVEL}\"");
        }
    }
}
