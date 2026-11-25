package org.gms.database;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.gms.i18n.LogBundle;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 迁移以文件名标识，common 先加载、方言目录同名覆盖，已应用的记录在 schema_version。
 */
@Slf4j
public abstract class SchemaMigrator {

    private static final String NAMESPACE = "core";

    protected final LogBundle logs;
    protected final Locale locale;

    protected SchemaMigrator(LogBundle logs, Locale locale) {
        this.logs = logs;
        this.locale = locale;
    }

    public final void migrate() {
        try {
            ensureDatabase();
            try (Connection connection = openConnection()) {
                createVersionTable(connection);
                Set<String> applied = appliedNames(connection);
                Map<String, Resource> scripts = resolve(dialect());

                int count = 0;
                for (Map.Entry<String, Resource> entry : scripts.entrySet()) {
                    if (applied.contains(entry.getKey())) {
                        continue;
                    }
                    log.debug("迁移 {} <- {}", entry.getKey(), entry.getValue().getDescription());
                    runScript(connection, entry.getValue());
                    record(connection, entry.getKey());
                    log.info(logs.get("db.migrated", locale, entry.getKey()));
                    count++;
                }
                if (count == 0) {
                    log.info(logs.get("db.uptodate", locale, scripts.size()));
                }
            }
        } catch (Exception e) {
            log.error(logs.get("db.failed", locale, e.getMessage()), e);
            throw new IllegalStateException("数据库初始化失败", e);
        }
    }

    /**
     * 建库：SQLite 建目录，MySQL/PostgreSQL 连到 server 级地址执行 CREATE DATABASE。
     */
    protected abstract void ensureDatabase() throws Exception;

    protected abstract String dialect();

    protected abstract Connection openConnection() throws SQLException;

    /**
     * 方言需要不同分隔符时覆盖，比如 SQL Server 的 GO 要配 setFullLineDelimiter(true)。
     */
    protected void configure(ScriptRunner runner) {
    }

    /**
     * 把 URL 里的库名换成指定值，query 参数原样保留（sslMode、connectionTimeZone 这类不能丢）。
     */
    protected static String serverUrl(String url, String database) {
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        if (slash < 0) {
            return url;
        }
        String base = url.substring(0, slash + 1) + database;
        int query = url.indexOf('?', slash);
        return query >= 0 ? base + url.substring(query) : base;
    }

    protected static String databaseName(String url) {
        int slash = url.indexOf('/', url.indexOf("//") + 2);
        if (slash < 0) {
            return "";
        }
        String rest = url.substring(slash + 1);
        int query = rest.indexOf('?');
        return query >= 0 ? rest.substring(0, query) : rest;
    }

    private void runScript(Connection connection, Resource script) throws IOException, SQLException {
        boolean autoCommit = connection.getAutoCommit();
        try (Reader reader = new InputStreamReader(script.getInputStream(), StandardCharsets.UTF_8)) {
            ScriptRunner runner = new ScriptRunner(connection);
            runner.setStopOnError(true);
            PrintWriter writer = new PrintWriter(logWriter(), true);
            runner.setLogWriter(writer);
            runner.setErrorLogWriter(writer);
            configure(runner);
            runner.runScript(reader);
        } finally {
            // ScriptRunner 会把 autoCommit 关掉且不恢复，后面的 record 需要它恢复默认才能落库
            connection.setAutoCommit(autoCommit);
        }
    }

    private static Writer logWriter() {
        return new Writer() {

            private final StringBuilder pending = new StringBuilder();

            @Override
            public void write(char[] chars, int offset, int length) {
                pending.append(chars, offset, length);
            }

            @Override
            public void flush() {
                String text = pending.toString().trim();
                if (!text.isEmpty()) {
                    log.debug(text);
                }
                pending.setLength(0);
            }

            @Override
            public void close() {
                flush();
            }
        };
    }

    private void createVersionTable(Connection connection) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS schema_version (
                    namespace VARCHAR(64) NOT NULL DEFAULT 'core',
                    name VARCHAR(128) NOT NULL,
                    applied_at INTEGER NOT NULL,
                    PRIMARY KEY (namespace, name)
                )""";
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Set<String> appliedNames(Connection connection) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT name FROM schema_version WHERE namespace = ?")) {
            statement.setString(1, NAMESPACE);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private Map<String, Resource> resolve(String dialect) throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, Resource> scripts = new TreeMap<>();
        for (Resource script : resolver.getResources("classpath*:db/migrate/common/*.sql")) {
            scripts.put(nameOf(script), script);
        }
        for (Resource script : resolver.getResources("classpath*:db/migrate/" + dialect + "/*.sql")) {
            scripts.put(nameOf(script), script);
        }
        return scripts;
    }

    private static String nameOf(Resource script) {
        String name = script.getFilename();
        return name == null ? "" : name.substring(0, name.length() - ".sql".length());
    }

    private void record(Connection connection, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version (namespace, name, applied_at) VALUES (?, ?, ?)")) {
            statement.setString(1, NAMESPACE);
            statement.setString(2, name);
            statement.setLong(3, Instant.now().toEpochMilli());
            statement.executeUpdate();
        }
    }
}
