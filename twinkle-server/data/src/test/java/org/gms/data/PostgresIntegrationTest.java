package org.gms.data;

import org.gms.data.config.ParamConfRepository;
import org.gms.data.entity.ParamConf;
import org.gms.data.migrate.MigrationRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL 双库验证（架构 6.2：SQLite 低配默认 / PG 大服）。
 *
 * <p>验证自研迁移器在 PG 上跑通 + CRUD 正常。这是 M0 验收点"连 SQLite 与 PG 双验证"的 PG 侧。
 *
 * <h2>如何运行</h2>
 * <ol>
 *   <li>起 PG：{@code docker run -d --name twinkle-pg -e POSTGRES_PASSWORD=twinkle -e POSTGRES_USER=twinkle
 *       -e POSTGRES_DB=twinkle_test -p 54321:5432 postgres:17}</li>
 *   <li>设环境变量：{@code export TWINKLE_PG_URL=jdbc:postgresql://localhost:54321/twinkle_test
 *       TWINKLE_PG_USER=twinkle TWINKLE_PG_PASSWORD=twinkle}</li>
 *   <li>跑：{@code mvn -pl data test -Dtest=PostgresIntegrationTest}</li>
 * </ol>
 *
 * <p>未设环境变量时测试被 {@link EnabledIfEnvironmentVariable} 跳过。
 */
@EnabledIfEnvironmentVariable(named = "TWINKLE_PG_URL", matches = ".+")
class PostgresIntegrationTest {

    private DataSource dataSource;
    private JdbcPostgresRepo repo;

    @BeforeEach
    void setUp() {
        String url = System.getenv("TWINKLE_PG_URL");
        String user = System.getenv().getOrDefault("TWINKLE_PG_USER", "twinkle");
        String pass = System.getenv().getOrDefault("TWINKLE_PG_PASSWORD", "twinkle");
        dataSource = new SimpleDriverDataSource(url, user, pass);
        // 清理旧表（保证可重复运行）
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS param_conf CASCADE");
            stmt.execute("DROP TABLE IF EXISTS schema_version CASCADE");
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
        // 跑迁移（PG 方言）
        MigrationRunner.applyMigrations(dataSource, "postgresql");
        repo = new JdbcPostgresRepo(dataSource);
    }

    @Test
    void migrationCreatesParamConfAndSeeds() {
        List<ParamConf> all = repo.selectAll();
        assertThat(all).isNotEmpty();
        // V1 seed 了 5 项
        assertThat(all).hasSize(5);
        assertThat(repo.selectByKey("game.level.rate")).isPresent();
    }

    @Test
    void crudUpsertWorksOnPostgres() {
        // insert 新键
        ParamConf fresh = new ParamConf("test.pg.key", "42");
        fresh.setVersion(1);
        fresh.setUpdatedAt("2026-08-06T00:00:00Z");
        repo.insert(fresh);
        assertThat(repo.selectByKey("test.pg.key")).isPresent();

        // update 已有键（模拟 DbConfigFacade.upsert 的路径）
        Optional<ParamConf> existing = repo.selectByKey("game.level.rate");
        assertThat(existing).isPresent();
        ParamConf p = existing.get();
        p.setConfigValue("2.5");
        p.setVersion(99);
        repo.update(p);

        ParamConf reloaded = repo.selectByKey("game.level.rate").orElseThrow();
        assertThat(reloaded.getConfigValue()).isEqualTo("2.5");
        assertThat(reloaded.getVersion()).isEqualTo(99);
    }

    @Test
    void secondMigrationRunIsNoOp() {
        int secondRun = MigrationRunner.applyMigrations(dataSource, "postgresql");
        assertThat(secondRun).isZero();
    }

    /**
     * 纯 JDBC 的 ParamConfRepository（复用测试结构，见 DbConfigFacadeTest 中的同类）。
     */
    static final class JdbcPostgresRepo implements ParamConfRepository {
        private final DataSource ds;
        JdbcPostgresRepo(DataSource ds) { this.ds = ds; }

        @Override
        public List<ParamConf> selectAll() {
            List<ParamConf> list = new java.util.ArrayList<>();
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement(
                         "SELECT id, config_key, config_value, version, updated_at FROM param_conf ORDER BY id");
                 var rs = ps.executeQuery()) {
                while (rs.next()) list.add(rowTo(rs));
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
            return list;
        }

        @Override
        public Optional<ParamConf> selectByKey(String configKey) {
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement(
                         "SELECT id, config_key, config_value, version, updated_at FROM param_conf WHERE config_key = ?")) {
                ps.setString(1, configKey);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rowTo(rs)) : Optional.empty();
                }
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void insert(ParamConf entity) {
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement(
                         "INSERT INTO param_conf (config_key, config_value, version, updated_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, entity.getConfigKey());
                ps.setString(2, entity.getConfigValue());
                ps.setLong(3, entity.getVersion());
                ps.setString(4, entity.getUpdatedAt());
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void update(ParamConf entity) {
            try (var conn = ds.getConnection();
                 var ps = conn.prepareStatement(
                         "UPDATE param_conf SET config_value=?, version=?, updated_at=? WHERE config_key=?")) {
                ps.setString(1, entity.getConfigValue());
                ps.setLong(2, entity.getVersion());
                ps.setString(3, entity.getUpdatedAt());
                ps.setString(4, entity.getConfigKey());
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
        }

        private static ParamConf rowTo(java.sql.ResultSet rs) throws java.sql.SQLException {
            ParamConf p = new ParamConf();
            p.setId(rs.getLong("id"));
            p.setConfigKey(rs.getString("config_key"));
            p.setConfigValue(rs.getString("config_value"));
            p.setVersion(rs.getLong("version"));
            p.setUpdatedAt(rs.getString("updated_at"));
            return p;
        }
    }
}
