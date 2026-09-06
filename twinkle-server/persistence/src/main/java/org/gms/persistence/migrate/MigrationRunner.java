package org.gms.persistence.migrate;


import javax.sql.DataSource;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;

/**
 * 自研迁移器（架构 6.2 决策：Flyway 社区版不支持 SQLite，三库统一自研迁移器）。
 *
 * <p>思路：版本表 {@code schema_version(version INT, name TEXT, applied_at TEXT)} + 类路径下
 * {@code db/migrate/V{n}__{name}.sql} 顺序执行。
 *
 * <h2>目录结构（数据库命名与迁移规范，禁 -- dialect 节）</h2>
 * <p>迁移目录分四类，方言差异靠"放哪个目录"表达，不用 SQL 内注释节：
 * <ul>
 *   <li>{@code db/migrate/common/}：三方言完全一致的语句（如 seed 数据、通用 DDL）。</li>
 *   <li>{@code db/migrate/sqlite/} / {@code postgresql/} / {@code mysql/}：各方言特有 DDL。</li>
 * </ul>
 * <p>执行顺序：按版本号升序；同一版本 common 与方言目录都有时，<b>方言目录优先</b>（方言覆盖 common）。
 *
 * <h2>脚本约定</h2>
 * <ul>
 *   <li>路径：{@code db/migrate/common/V1__seed.sql}、{@code db/migrate/sqlite/V1__init.sql}（双下划线分隔版本号与名称）。</li>
 *   <li>语句分隔：{@code ;}（不写存储过程，避免方言差异）。</li>
 *   <li>方言差异禁止用 {@code -- dialect:xxx} 注释节（旧机制废弃），必须拆目录。</li>
 * </ul>
 *
 * <h2>为什么不用 Flyway</h2>
 * Flyway 社区版自 V10 起撤掉了 SQLite，仅 MySQL/Postgres 等保留。商业版才有 SQLite 模块。
 * 架构默认 SQLite（低配档），必须自研。
 *
 * <h2>2C2G 预算</h2>
 * 启动时一次性顺序执行，毫秒级关闭；不挂后台线程。
 */
@Log4j2
public final class MigrationRunner {



    private static final String VERSION_TABLE = "schema_version";
    private static final int LOCK_TIMEOUT_SECONDS = 60;
    private static final String MYSQL_LOCK_NAME = "twinkle_schema_migration";
    private static final long POSTGRES_LOCK_KEY = 0x5457494E4B4C45L;
    private static final String MIGRATE_DIR = "db/migrate/";
    /** 全方言共用目录。 */
    private static final String COMMON_DIR = "db/migrate/common/";
    /** 方言目录前缀（{@code db/migrate/{dialectId}/}）。 */
    private static final String DIALECT_DIR_PREFIX = "db/migrate/";

    private final DataSource dataSource;
    private final String dialectId;

    public MigrationRunner(DataSource dataSource, String dialectId) {
        this.dataSource = dataSource;
        this.dialectId = dialectId.toLowerCase(Locale.ROOT);
    }

    /**
     * 执行所有未应用的迁移。返回本次新增的版本数。
     */
    public int run() throws SQLException {
        long startedAt = System.nanoTime();
        TreeMap<Integer, String> files = discoverMigrations();
        if (files.isEmpty()) {
            log.info(I18n.message("log.migrate.none"));
            return 0;
        }

        int applied = 0;
        int alreadyApplied = 0;
        try (Connection conn = dataSource.getConnection()) {
            log.info(I18n.message("log.migrate.lock_wait"), dialectId);
            acquireMigrationLock(conn);
            log.info(I18n.message("log.migrate.lock_acquired"), dialectId);
            try {
                ensureVersionTable(conn);
                for (var entry : files.entrySet()) {
                    int version = entry.getKey();
                    String name = entry.getValue();
                    if (isApplied(conn, version)) {
                        alreadyApplied++;
                        continue;
                    }
                    log.info(I18n.message("log.migrate.applied"), version, name);
                    String sql = loadResource(name);
                    List<String> statements = splitStatements(sql);
                    for (String stmt : statements) {
                        if (stmt.isBlank()) continue;
                        try (Statement st = conn.createStatement()) {
                            st.execute(stmt);
                        }
                    }
                    recordApplied(conn, version, name);
                    applied++;
                }
                commitMigration(conn);
            } catch (SQLException | RuntimeException e) {
                try {
                    rollbackMigration(conn);
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            } finally {
                releaseMigrationLock(conn);
            }
        }
        long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        log.info(I18n.message("log.migrate.summary"), files.size(), alreadyApplied, applied, elapsedMillis);
        return applied;
    }

    private void ensureVersionTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " ("
                    + "version INTEGER PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /**
     * 获取数据库级迁移互斥锁。锁覆盖建版本表、重新读取版本、执行脚本和记录版本的完整过程。
     * 等待者拿到锁后才读取 {@code schema_version}，不会使用等待前的陈旧版本快照。
     */
    private void acquireMigrationLock(Connection conn) throws SQLException {
        switch (dialectId) {
            case "sqlite" -> {
                // busy_timeout 是连接级 PRAGMA；迁移连接必须单独设置，不能依赖工厂的其他连接。
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA busy_timeout=" + (LOCK_TIMEOUT_SECONDS * 1_000));
                    st.execute("BEGIN IMMEDIATE");
                }
            }
            case "postgresql" -> {
                conn.setAutoCommit(false);
                try (var ps = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    ps.setLong(1, POSTGRES_LOCK_KEY);
                    ps.executeQuery();
                }
            }
            case "mysql" -> {
                try (var ps = conn.prepareStatement("SELECT GET_LOCK(?, ?)")) {
                    ps.setString(1, MYSQL_LOCK_NAME);
                    ps.setInt(2, LOCK_TIMEOUT_SECONDS);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || rs.getInt(1) != 1) {
                            throw new SQLException("Timed out waiting for MySQL migration lock");
                        }
                    }
                }
                conn.setAutoCommit(false);
            }
            default -> throw new SQLException("Unsupported migration dialect: " + dialectId);
        }
    }

    private void commitMigration(Connection conn) throws SQLException {
        if ("sqlite".equals(dialectId)) {
            try (Statement st = conn.createStatement()) {
                st.execute("COMMIT");
            }
        } else {
            conn.commit();
        }
    }

    private void rollbackMigration(Connection conn) throws SQLException {
        if ("sqlite".equals(dialectId)) {
            try (Statement st = conn.createStatement()) {
                st.execute("ROLLBACK");
            }
        } else {
            conn.rollback();
        }
    }

    private void releaseMigrationLock(Connection conn) {
        if (!"mysql".equals(dialectId)) {
            return;
        }
        try (var ps = conn.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, MYSQL_LOCK_NAME);
            ps.executeQuery();
        } catch (SQLException e) {
            // 连接关闭也会释放 MySQL named lock；此处不覆盖迁移本身的成功或失败结果。
            log.warn("Failed to release MySQL migration lock; closing the connection will release it", e);
        }
    }

    private boolean isApplied(Connection conn, int version) throws SQLException {
        try (var ps = conn.prepareStatement("SELECT 1 FROM " + VERSION_TABLE + " WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void recordApplied(Connection conn, int version, String name) throws SQLException {
        try (var ps = conn.prepareStatement("INSERT INTO " + VERSION_TABLE + " (version, name) VALUES (?, ?)")) {
            ps.setInt(1, version);
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    /**
     * 发现迁移：合并 common 与方言目录，按版本号升序，方言优先覆盖 common。
     *
     * <p>返回的 name 是<b>完整资源路径</b>（如 {@code db/migrate/sqlite/V1__init.sql}），
     * 便于 {@link #loadResource} 直接加载；版本 → 路径映射。
     */
    private TreeMap<Integer, String> discoverMigrations() {
        TreeMap<Integer, String> result = new TreeMap<>();
        // 先扫 common（全方言共用），方言目录后扫覆盖同版本
        scanDirectory(COMMON_DIR, result);
        scanDirectory(DIALECT_DIR_PREFIX + dialectId + "/", result);
        return result;
    }

    /** 扫描单个目录下的 .sql 迁移（file 或 jar 协议），版本冲突时后扫者覆盖（方言优先）。 */
    private void scanDirectory(String dir, TreeMap<Integer, String> result) {
        try {
            var urls = Thread.currentThread().getContextClassLoader().getResources(dir);
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                if (url.getProtocol().equals("file")) {
                    java.nio.file.Path path = java.nio.file.Paths.get(url.toURI());
                    try (var stream = java.nio.file.Files.list(path)) {
                        stream.filter(p -> p.getFileName().toString().endsWith(".sql"))
                                .forEach(p -> parseAndPut(dir + p.getFileName().toString(), result));
                    }
                } else if (url.getProtocol().equals("jar")) {
                    String fullPath = url.getPath();
                    int sep = fullPath.indexOf("!");
                    String jarPath = fullPath.substring(0, sep);
                    String entryPrefix = fullPath.substring(sep + 2);
                    try (var jar = new java.util.jar.JarFile(
                            java.nio.file.Paths.get(java.net.URI.create(jarPath)).toFile())) {
                        var entries = jar.entries();
                        while (entries.hasMoreElements()) {
                            var entry = entries.nextElement();
                            String name = entry.getName();
                            if (name.startsWith(entryPrefix) && name.endsWith(".sql")) {
                                parseAndPut(dir + name.substring(entryPrefix.length()), result);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error(I18n.message("log.migrate.scan_failed"), dir, e);
        }
    }

    /** 解析文件名（{@code V{n}__{name}.sql}），放入 version → 完整路径 映射。 */
    private static void parseAndPut(String fullResourcePath, TreeMap<Integer, String> map) {
        String filename = fullResourcePath.substring(fullResourcePath.lastIndexOf('/') + 1);
        if (!filename.startsWith("V") || !filename.contains("__")) {
            return;
        }
        try {
            int version = Integer.parseInt(filename.substring(1, filename.indexOf("__")));
            map.put(version, fullResourcePath);
        } catch (NumberFormatException e) {
            log.warn(I18n.message("log.migrate.bad_filename"), filename);
        }
    }

    private static String loadResource(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(I18n.message("error.migrate.resource_missing", path));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(I18n.message("error.migrate.load_failed", path), e);
        }
    }

    /**
     * 简单 SQL 切分：按 {@code ;} 分行；不处理字符串内的分号（迁移脚本里 {@code ;} 极少出现在引号内）。
     */
    private static List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        for (String stmt : sql.split(";")) {
            String trimmed = stmt.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 静态入口，方便外部调用。 */
    public static int applyMigrations(DataSource dataSource, String dialectId) {
        try {
            return new MigrationRunner(dataSource, dialectId).run();
        } catch (SQLException e) {
            throw new RuntimeException(I18n.message("error.migrate.failed"), e);
        }
    }
}
