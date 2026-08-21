package org.gms.data;

import org.gms.i18n.I18n;
import org.gms.i18n.ResourceBundleI18nService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseProvisionerTest {

    @BeforeAll
    static void installI18n() {
        I18n.install(new ResourceBundleI18nService("zh-CN"));
    }

    @AfterAll
    static void resetI18n() {
        I18n.install(null);
    }

    @Test
    void parsesMysqlUrlAndPreservesConnectionOptions() {
        DatabaseProvisioner.DatabaseTarget target =
                DatabaseProvisioner.DatabaseTarget.parse(
                        "jdbc:mysql://db.example:3306/twinkle?useSSL=false&serverTimezone=Asia/Shanghai");

        assertThat(target.dialect()).isEqualTo(DatabaseProvisioner.Dialect.MYSQL);
        assertThat(target.databaseName()).isEqualTo("twinkle");
        assertThat(target.adminUrl()).isEqualTo(
                "jdbc:mysql://db.example:3306/mysql?useSSL=false&serverTimezone=Asia/Shanghai");
    }

    @Test
    void parsesPostgresqlUrlAndDecodesDatabaseName() {
        DatabaseProvisioner.DatabaseTarget target =
                DatabaseProvisioner.DatabaseTarget.parse(
                        "jdbc:postgresql://db.example:5432/twinkle%2Dtest?sslmode=require");

        assertThat(target.dialect()).isEqualTo(DatabaseProvisioner.Dialect.POSTGRESQL);
        assertThat(target.databaseName()).isEqualTo("twinkle-test");
        assertThat(target.adminUrl()).isEqualTo(
                "jdbc:postgresql://db.example:5432/postgres?sslmode=require");
    }

    @Test
    void ignoresSqliteUrl() {
        assertThat(DatabaseProvisioner.DatabaseTarget.parse("jdbc:sqlite:./data/twinkle.db"))
                .isNull();
    }

    @Test
    void rejectsNetworkUrlWithoutDatabaseName() {
        assertThatThrownBy(() -> DatabaseProvisioner.DatabaseTarget.parse(
                "jdbc:mysql://localhost:3306/?useSSL=false"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少数据库名");
    }

    @Test
    void quotesDatabaseIdentifiersWithoutSqlInjection() {
        assertThat(DatabaseProvisioner.mysqlIdentifier("game`prod"))
                .isEqualTo("`game``prod`");
        assertThat(DatabaseProvisioner.postgresqlIdentifier("game\"prod"))
                .isEqualTo("\"game\"\"prod\"");
    }
}
