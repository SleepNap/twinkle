package org.gms.data;

import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 在正式数据源连接目标库之前，为 MySQL/PostgreSQL 创建尚不存在的数据库。
 *
 * <p>配置仍由 Micronaut 统一解析并注入；本类只拆解最终 JDBC URL，不读取 YAML。
 * 创建数据库时不指定字符集、排序规则、编码或区域设置，分别继承 MySQL 服务器和
 * PostgreSQL 集群模板的默认配置，避免与现有实例产生 collation/locale 混用。
 */
@Log4j2
final class DatabaseProvisioner {

    private static final String MYSQL_PREFIX = "jdbc:mysql://";
    private static final String POSTGRESQL_PREFIX = "jdbc:postgresql://";

    private DatabaseProvisioner() {
    }

    static void ensureDatabaseExists(String jdbcUrl, String user, String password) {
        DatabaseTarget target = DatabaseTarget.parse(jdbcUrl);
        if (target == null) {
            return;
        }

        try (Connection connection = new SimpleDriverDataSource(target.adminUrl(), user, password)
                .getConnection()) {
            if (databaseExists(connection, target)) {
                log.debug(I18n.message("log.data.database_exists"), target.databaseName());
                return;
            }
            createDatabase(connection, target);
            log.info(I18n.message("log.data.database_created"), target.databaseName());
        } catch (SQLException e) {
            throw new IllegalStateException(I18n.message(
                    "error.data.database_bootstrap_failed", target.databaseName(), e.getMessage()), e);
        }
    }

    private static boolean databaseExists(Connection connection, DatabaseTarget target) throws SQLException {
        String sql = switch (target.dialect()) {
            case MYSQL -> "SELECT 1 FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?";
            case POSTGRESQL -> "SELECT 1 FROM pg_database WHERE datname = ?";
        };
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, target.databaseName());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void createDatabase(Connection connection, DatabaseTarget target) throws SQLException {
        // PostgreSQL 的 CREATE DATABASE 不能在事务块中执行；JDBC 默认即为 true，这里显式固化。
        connection.setAutoCommit(true);
        String sql = switch (target.dialect()) {
            // 不写 CHARACTER SET / COLLATE：继承 character_set_server / collation_server。
            case MYSQL -> "CREATE DATABASE IF NOT EXISTS " + mysqlIdentifier(target.databaseName());
            // 不写 ENCODING / LC_COLLATE / LC_CTYPE：继承 postgres 模板库配置。
            case POSTGRESQL -> "CREATE DATABASE " + postgresqlIdentifier(target.databaseName());
        };
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            // 多进程并发首次启动时，另一个进程可能在检查后抢先创建 PG 数据库。
            if (target.dialect() == Dialect.POSTGRESQL && "42P04".equals(e.getSQLState())) {
                return;
            }
            throw e;
        }
    }

    static String mysqlIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String postgresqlIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    enum Dialect {
        MYSQL,
        POSTGRESQL
    }

    record DatabaseTarget(Dialect dialect, String adminUrl, String databaseName) {

        static DatabaseTarget parse(String jdbcUrl) {
            if (jdbcUrl == null) {
                return null;
            }
            if (jdbcUrl.startsWith(MYSQL_PREFIX)) {
                return parseNetworkUrl(jdbcUrl, MYSQL_PREFIX, "mysql", Dialect.MYSQL);
            }
            if (jdbcUrl.startsWith(POSTGRESQL_PREFIX)) {
                return parseNetworkUrl(jdbcUrl, POSTGRESQL_PREFIX, "postgres", Dialect.POSTGRESQL);
            }
            return null;
        }

        private static DatabaseTarget parseNetworkUrl(
                String jdbcUrl, String prefix, String adminDatabase, Dialect dialect) {
            int optionsStart = jdbcUrl.indexOf('?');
            String base = optionsStart < 0 ? jdbcUrl : jdbcUrl.substring(0, optionsStart);
            String options = optionsStart < 0 ? "" : jdbcUrl.substring(optionsStart);
            int databaseSeparator = base.lastIndexOf('/');
            if (databaseSeparator < prefix.length() || databaseSeparator == base.length() - 1) {
                // 错误中不带 query，避免 JDBC 参数内可能存在的敏感信息进入日志。
                throw new IllegalArgumentException(I18n.message("error.data.database_name_missing", base));
            }

            String encodedName = base.substring(databaseSeparator + 1);
            String databaseName = URLDecoder.decode(
                    encodedName.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (databaseName.isBlank() || databaseName.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(I18n.message("error.data.database_name_missing", base));
            }
            String adminUrl = base.substring(0, databaseSeparator + 1) + adminDatabase + options;
            return new DatabaseTarget(dialect, adminUrl, databaseName);
        }
    }
}
