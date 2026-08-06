package org.gms.data;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.dialect.DbDialectRegistry;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据源工厂（架构 6.2 三档切换 + SQLite 三件套）。
 *
 * <h2>SQLite 三件套（红线）</h2>
 * <ul>
 *   <li>{@code journal_mode=WAL}：读写并行，读者永不阻塞。</li>
 *   <li>{@code busy_timeout=100}：瞬态互斥等待而非报错。</li>
 *   <li>单写连接由 {@code SingleWriteExecutor} 承担（不通过池化，仅低配档使用，M1 落地）。</li>
 * </ul>
 *
 * <h2>Postgres/MySQL 走标准 JDBC</h2>
 * <p>M0 阶段用 {@link SimpleDriverDataSource}（DriverManager 节流版），避免引入池化库的额外常驻内存
 * （2C2G 红线 9.1）。后续大服阶段再按需引入 HikariCP（pool 在 2C2G 档不启用）。
 *
 * <p>连接串形态：{@code twinkle.db.url=jdbc:sqlite:./data/twinkle.db} 或 {@code :memory:}。
 */
@Factory
public class DataSourceFactory {

    private static final Logger LOG = LogManager.getLogger(DataSourceFactory.class);

    @Bean
    @Singleton
    @Primary
    @Context  // 启动即装配（@Context）：M0 验收要求"能启动即连库跑迁移"，不依赖懒加载触发
    public DataSource dataSource(
            @Property(name = "twinkle.db.url") String url,
            @Property(name = "twinkle.db.user", defaultValue = "") String user,
            @Property(name = "twinkle.db.password", defaultValue = "") String password,
            DbDialectRegistry dialectRegistry) {
        LOG.info("初始化数据源: {}", maskCredentials(url));
        if (url.startsWith("jdbc:sqlite:") && !url.contains(":memory:")) {
            ensureSqliteDir(url);
        }
        SimpleDriverDataSource ds = new SimpleDriverDataSource(url, user, password);
        if (url.startsWith("jdbc:sqlite")) {
            applySqlitePragmas(ds);
        }
        // 迁移随 DataSource 创建即跑：保证任何 Repository/Facade 实例化前 schema 就绪。
        // 这是架构 6.2 "启动时跑迁移"的正确顺序（此前放 ServerStartupEvent，晚于 bean 创建，
        // 导致 DbConfigFacade 构造时 param_conf 尚不存在）。
        org.gms.data.migrate.MigrationRunner.applyMigrations(ds,
                dialectRegistry.resolveByUrl(url).id().name().toLowerCase());
        return ds;
    }

    /**
     * SQLite 文件型数据库：确保父目录存在（SQLite 不会自动创建父目录，缺目录即
     * SQLITE_CANTOPEN）。内存库（:memory:）跳过。
     */
    private void ensureSqliteDir(String url) {
        String filePath = url.substring("jdbc:sqlite:".length());
        Path dir = Path.of(filePath).toAbsolutePath().getParent();
        if (dir == null) return;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 SQLite 数据目录: " + dir, e);
        }
    }

    /**
     * 在启动时 SQLite 优化：WAL + busy_timeout + synchronous=NORMAL + foreign_keys。
     */
    private void applySqlitePragmas(DataSource ds) {
        try (Connection conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=100");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) {
            LOG.warn("SQLite PRAGMA 应用失败（部分数据库可能不支持）", e);
        }
    }

    private static String maskCredentials(String url) {
        if (url == null) return "(null)";
        return url.replaceAll("password=[^&;]*", "password=***");
    }
}
