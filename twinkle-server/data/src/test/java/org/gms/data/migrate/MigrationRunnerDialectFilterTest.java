package org.gms.data.migrate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方言过滤逻辑测试（架构 6.2：方言差异收敛到迁移脚本，业务代码不出现裸方言）。
 */
class MigrationRunnerDialectFilterTest {

    @Test
    void sqliteSectionOnlyKeepsSqliteBlock() {
        String sql = """
                -- 头部注释（全方言）
                -- dialect:sqlite
                CREATE TABLE t (id INTEGER);
                -- dialect:postgresql
                CREATE TABLE t (id BIGINT);
                -- dialect:mysql
                CREATE TABLE t (id BIGINT);
                """;
        String filtered = MigrationRunner.filterForDialect(sql, "sqlite");
        assertThat(filtered).contains("id INTEGER").doesNotContain("id BIGINT");
    }

    @Test
    void postgresqlSectionOnlyKeepsPostgresBlock() {
        String sql = """
                -- dialect:sqlite
                CREATE TABLE t (id INTEGER);
                -- dialect:postgresql
                CREATE TABLE t (id BIGINT);
                """;
        String filtered = MigrationRunner.filterForDialect(sql, "postgresql");
        assertThat(filtered).contains("id BIGINT").doesNotContain("id INTEGER");
    }

    @Test
    void blankLineResetsSectionToAllDialects() {
        String sql = "-- dialect:sqlite\n"
                + "CREATE TABLE t (id INTEGER);\n"
                + "\n"  // 空行：重置节
                + "CREATE INDEX idx ON t(id);\n";
        String filtered = MigrationRunner.filterForDialect(sql, "postgresql");
        // CREATE INDEX 是全方言，postgres 也应保留
        assertThat(filtered).contains("CREATE INDEX").doesNotContain("id INTEGER");
    }

    @Test
    void inlineCommentInsideSectionDoesNotResetSection() {
        // 行内注释（CREATE TABLE 块内的说明）不重置节——关键防泄漏点
        String sql = """
                -- dialect:postgresql
                CREATE TABLE t (
                    -- 这是行内注释
                    id BIGINT
                );
                """;
        String filtered = MigrationRunner.filterForDialect(sql, "postgresql");
        assertThat(filtered).contains("id BIGINT");
        assertThat(filtered).contains("行内注释");
    }

    @Test
    void unmarkedStatementsApplyToAllDialects() {
        String sql = """
                CREATE TABLE shared (id INT);
                """;
        assertThat(MigrationRunner.filterForDialect(sql, "sqlite")).contains("CREATE TABLE shared");
        assertThat(MigrationRunner.filterForDialect(sql, "postgresql")).contains("CREATE TABLE shared");
    }

    @Test
    void dialectMarkerLineItselfIsRemoved() {
        String sql = "-- dialect:sqlite\nCREATE TABLE t (id INTEGER);";
        String filtered = MigrationRunner.filterForDialect(sql, "sqlite");
        assertThat(filtered).doesNotContain("dialect:sqlite");
        assertThat(filtered).contains("CREATE TABLE t");
    }
}
