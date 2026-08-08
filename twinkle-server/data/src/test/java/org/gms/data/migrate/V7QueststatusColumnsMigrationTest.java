package org.gms.data.migrate;

import org.gms.data.SimpleDriverDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7 迁移验证（架构 M5-2 单库迁移）：queststatus 补 newmaple 四列。
 *
 * <p>验证：
 * <ol>
 *   <li>临时 SQLite 全量 V1-V7 跑迁移后，queststatus 含 expires/forfeited/completed/info 四列。</li>
 *   <li>schema_version 记录 V7。</li>
 *   <li>V7 脚本方言节过滤：sqlite 节含 4 条 ALTER（每列独立一条，红线 2 禁止串接多列）；
 *       postgresql/mysql 节各自只含本方言 ALTER。</li>
 * </ol>
 */
class V7QueststatusColumnsMigrationTest {

    @Test
    void queststatusGetsNewmapleColumnsAfterV1ToV7() throws Exception {
        String dbPath = Files.createTempDirectory("twinkle-v7-test").resolve("test.db").toString();
        DataSource ds = new SimpleDriverDataSource("jdbc:sqlite:" + dbPath, "", "");
        MigrationRunner.applyMigrations(ds, "sqlite");

        try (Connection conn = ds.getConnection()) {
            // schema_version 含 V7
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
                Set<Integer> versions = new HashSet<>();
                while (rs.next()) {
                    versions.add(rs.getInt(1));
                }
                assertThat(versions).contains(7);
            }

            // queststatus 含四新列
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(queststatus)")) {
                Set<String> columns = new HashSet<>();
                while (rs.next()) {
                    columns.add(rs.getString("name"));
                }
                assertThat(columns).contains("expires", "forfeited", "completed", "info");
            }
        }
    }

    @Test
    void v7DialectSectionsKeepOnlyOwnAlters() {
        String sql = loadV7();
        // 每条 ALTER 只 ADD 一列（红线 2：禁止单条 ALTER 串接多列）
        for (String dialect : new String[]{"sqlite", "postgresql", "mysql"}) {
            String filtered = MigrationRunner.filterForDialect(sql, dialect);
            long alterCount = filtered.lines()
                    .filter(l -> l.trim().startsWith("ALTER TABLE"))
                    .count();
            assertThat(alterCount).as(dialect + " 节应有 4 条独立 ALTER").isEqualTo(4);
        }
        // sqlite 节不得泄漏其他方言类型；各自含本方言 ALTER
        assertThat(MigrationRunner.filterForDialect(sql, "sqlite"))
                .contains("ALTER TABLE queststatus ADD COLUMN expires INTEGER")
                .doesNotContain("BIGINT");
        assertThat(MigrationRunner.filterForDialect(sql, "postgresql"))
                .contains("ALTER TABLE queststatus ADD COLUMN expires BIGINT")
                .doesNotContain("`expires`");
        assertThat(MigrationRunner.filterForDialect(sql, "mysql"))
                .contains("`expires` BIGINT(20)")
                .doesNotContain("ADD COLUMN expires INTEGER");
    }

    private static String loadV7() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (java.io.InputStream in = cl.getResourceAsStream("db/migrate/V7__queststatus_newmaple_columns.sql")) {
            if (in == null) {
                throw new IllegalStateException("V7 迁移脚本不存在");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("加载 V7 失败", e);
        }
    }
}
