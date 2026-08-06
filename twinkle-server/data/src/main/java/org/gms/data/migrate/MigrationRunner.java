package org.gms.data.migrate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
import java.util.TreeMap;

/**
 * 自研迁移器（架构 6.2 决策：Flyway 社区版不支持 SQLite，三库统一自研迁移器）。
 *
 * <p>思路：版本表 {@code schema_version(version INT, name TEXT, applied_at TEXT)} + 类路径下
 * {@code db/migrate/V{n}__{name}.sql} 顺序执行。SQLite/PG/MySQL 三库共享同一脚本源，方言差异点通过
 * SQL 注释（{@code -- dialect:sqlite} / {@code -- dialect:postgresql} / {@code -- dialect:mysql}）
 * 限定执行。
 *
 * <h2>脚本约定</h2>
 * <ul>
 *   <li>路径：{@code src/main/resources/db/migrate/V1__init.sql}（双下划线分隔版本号与名称）。</li>
 *   <li>语句分隔：{@code ;}（不写存储过程，避免方言差异）。</li>
 *   <li>方言分支：{@code -- dialect:xxx} 起始的节属于该方言，直到下一个节标记或空行；
 *       无节包裹的语句所有方言执行。设计意图：把方言差异收敛到迁移脚本里（受控位置），
 *       不要在业务代码里出现（红线 14）。</li>
 * </ul>
 *
 * <h2>为什么不用 Flyway</h2>
 * Flyway 社区版自 V10 起撤掉了 SQLite，仅 MySQL/Postgres 等保留。商业版才有 SQLite 模块。
 * 架构默认 SQLite（低配档），必须自研。
 *
 * <h2>2C2G 预算</h2>
 * 启动时一次性顺序执行，毫秒级关闭；不挂后台线程。
 */
public final class MigrationRunner {

    private static final Logger LOG = LogManager.getLogger(MigrationRunner.class);

    private static final String VERSION_TABLE = "schema_version";
    private static final String MIGRATE_DIR = "db/migrate/";

    private final DataSource dataSource;
    private final String dialectId;

    public MigrationRunner(DataSource dataSource, String dialectId) {
        this.dataSource = dataSource;
        this.dialectId = dialectId;
    }

    /**
     * 执行所有未应用的迁移。返回本次新增的版本数。
     */
    public int run() throws SQLException {
        ensureVersionTable();
        TreeMap<Integer, String> files = discoverMigrations();
        if (files.isEmpty()) {
            LOG.info("无可用迁移脚本");
            return 0;
        }

        int applied = 0;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            for (var entry : files.entrySet()) {
                int version = entry.getKey();
                String name = entry.getValue();
                if (isApplied(conn, version)) {
                    continue;
                }
                LOG.info("应用迁移 V{}: {}", version, name);
                String sql = loadResource(MIGRATE_DIR + "V" + version + "__" + name + ".sql");
                List<String> statements = splitStatements(filterForDialect(sql, dialectId));
                for (String stmt : statements) {
                    if (stmt.isBlank()) continue;
                    try (Statement st = conn.createStatement()) {
                        st.execute(stmt);
                    }
                }
                recordApplied(conn, version, name);
                applied++;
            }
            conn.commit();
        }
        if (applied == 0) {
            LOG.info("数据库已是最新（跳过 {} 个已应用迁移）", files.size());
        } else {
            LOG.info("本次共应用 {} 个迁移", applied);
        }
        return applied;
    }

    private void ensureVersionTable() throws SQLException {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " ("
                    + "version INTEGER PRIMARY KEY, "
                    + "name TEXT NOT NULL, "
                    + "applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
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

    private TreeMap<Integer, String> discoverMigrations() {
        TreeMap<Integer, String> result = new TreeMap<>();
        try {
            var urls = Thread.currentThread().getContextClassLoader().getResources(MIGRATE_DIR);
            while (urls.hasMoreElements()) {
                var url = urls.nextElement();
                if (url.getProtocol().equals("file")) {
                    // IDE 开发期：文件系统目录
                    java.nio.file.Path dir = java.nio.file.Paths.get(url.toURI());
                    try (var stream = java.nio.file.Files.list(dir)) {
                        stream.filter(p -> p.getFileName().toString().endsWith(".sql"))
                                .forEach(p -> parseAndPut(p.getFileName().toString(), result));
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
                                parseAndPut(name.substring(entryPrefix.length()), result);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("扫描迁移目录失败", e);
        }
        return result;
    }

    private static void parseAndPut(String filename, TreeMap<Integer, String> map) {
        if (!filename.startsWith("V") || !filename.contains("__")) {
            return;
        }
        try {
            int version = Integer.parseInt(filename.substring(1, filename.indexOf("__")));
            String name = filename.substring(filename.indexOf("__") + 2, filename.length() - 4);
            map.put(version, name);
        } catch (NumberFormatException e) {
            LOG.warn("迁移文件名格式异常: {}", filename);
        }
    }

    private static String loadResource(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("迁移资源不存在: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("加载迁移失败: " + path, e);
        }
    }

    /**
     * 执行方言过滤。
     *
     * <p>方言标记以"节"为单位：{@code -- dialect:sqlite} 之后的语句属于该节，直到遇到下一个
     * {@code -- dialect:} 标记，或遇到空行（回到"全方言适用"）。无节包裹的语句对所有方言执行。
     *
     * <p>注释行（{@code -- ...}）不重置节：允许在 CREATE TABLE 多行块中写行内注释（如
     * {@code -- updated_at 统一存 ISO 8601}），不必担心泄漏到其他方言。
     */
    static String filterForDialect(String sql, String dialectId) {
        StringBuilder out = new StringBuilder();
        String currentSection = null; // null = 全方言适用
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-- dialect:")) {
                currentSection = trimmed.substring("-- dialect:".length()).trim();
                continue;
            }
            // 空行 → 重置节（回到"全方言适用"）
            if (trimmed.isEmpty()) {
                currentSection = null;
                out.append(line).append('\n');
                continue;
            }
            if (currentSection == null || currentSection.equals(dialectId)) {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * 简单 SQL 切分：按 {@code ;} 分行；不处理字符串内的分号（迁移脚本里 {@code ;} 极少出现在引号内）。
     */
    static List<String> splitStatements(String sql) {
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
            throw new RuntimeException("迁移失败", e);
        }
    }
}
