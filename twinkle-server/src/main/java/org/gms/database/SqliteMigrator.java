package org.gms.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import org.gms.i18n.LogBundle;

/**
 * SQLite 的「建库」就是建出库文件所在的目录，SQLite 不会自动创建父目录。
 */
public final class SqliteMigrator extends SchemaMigrator {

    private static final String PREFIX = "jdbc:sqlite:";

    private final String url;

    public SqliteMigrator(String url, LogBundle logs, Locale locale) {
        super(logs, locale);
        this.url = url;
    }

    @Override
    protected void ensureDatabase() throws IOException {
        String path = url.substring(PREFIX.length());
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.isEmpty() || path.startsWith(":memory:")) {
            return;
        }
        Path parent = Path.of(path).toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @Override
    protected String dialect() {
        return "sqlite";
    }

    @Override
    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url);
    }
}
