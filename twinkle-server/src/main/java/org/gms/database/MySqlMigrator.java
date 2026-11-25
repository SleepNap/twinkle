package org.gms.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.gms.i18n.LogBundle;

/**
 * MySQL 的库不存在时连不上，先连到 server 级地址（不带库名）再把库建出来。
 */
public final class MySqlMigrator extends SchemaMigrator {

    private final String url;
    private final String username;
    private final String password;

    public MySqlMigrator(String url, String username, String password, LogBundle logs, Locale locale) {
        super(logs, locale);
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    protected void ensureDatabase() throws SQLException {
        String database = databaseName(url);
        if (database.isEmpty()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverUrl(url, ""), username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE IF NOT EXISTS `" + database + "` DEFAULT CHARACTER SET utf8mb4");
        }
    }

    @Override
    protected String dialect() {
        return "mysql";
    }

    @Override
    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
