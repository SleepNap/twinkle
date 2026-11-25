package org.gms.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import org.gms.i18n.LogBundle;

/**
 * PostgreSQL 没有 CREATE DATABASE IF NOT EXISTS，先连 postgres 库查 pg_database 再建。
 */
public final class PostgresMigrator extends SchemaMigrator {

    private final String url;
    private final String username;
    private final String password;

    public PostgresMigrator(String url, String username, String password, LogBundle logs, Locale locale) {
        super(logs, locale);
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    protected void ensureDatabase() throws SQLException {
        String database = databaseName(url);
        if (database.isEmpty() || database.equals("postgres")) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(serverUrl(url, "postgres"), username, password)) {
            if (exists(connection, database)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE \"" + database + "\"");
            }
        }
    }

    private static boolean exists(Connection connection, String database) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            statement.setString(1, database);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    @Override
    protected String dialect() {
        return "postgresql";
    }

    @Override
    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }
}
