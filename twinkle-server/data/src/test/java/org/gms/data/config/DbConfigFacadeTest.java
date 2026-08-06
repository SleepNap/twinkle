package org.gms.data.config;

import org.gms.config.ConfigChangeEvent;
import org.gms.data.SimpleDriverDataSource;
import org.gms.data.entity.ParamConf;
import org.gms.event.InProcessEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M0 验收点：L1 配置热更新（架构 5.2）。
 *
 * <p>场景：{@link DbConfigFacade#upsert} 写 param_conf → 触发版本号 +1 + EventBus 广播
 * → 订阅者（DbConfigFacade 自己）重读 → 二次 {@code get(key)} 拿到新值。验证"改 DB → 热生效"链路
 * 不通就需要重启。
 *
 * <h2>为什么手写 JDBC Repository 而不用 MyBatis-Flex</h2>
 * <p>M0 阶段测试不想启动 SqlSessionFactory（涉及 dialect 探测 + mapper 注册较多配置）。
 * DbConfigFacade 依赖的是 {@link ParamConfRepository} 接口——测试给个 JDBC 实现即可，
 * 聚焦 facade 自身逻辑（缓存 + 广播）。M1 起接入 MyBatis-Flex SqlSessionFactory 后，
 * 这个测试不需要改（换成真实实现即可）。
 */
class DbConfigFacadeTest {

    private DataSource dataSource;
    private DbConfigFacade facade;
    private InProcessEventBus eventBus;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test.db");
        String url = "jdbc:sqlite:" + dbFile.toString();
        dataSource = new SimpleDriverDataSource(url, "", "");
        // SQLite PRAGMA
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
            stmt.execute("PRAGMA busy_timeout=100");
            stmt.execute("PRAGMA synchronous=NORMAL");
            stmt.execute("PRAGMA foreign_keys=ON");
        }
        // 跑迁移（V1 含 param_conf 建表 + seed）
        org.gms.data.migrate.MigrationRunner.applyMigrations(dataSource, "sqlite");
        eventBus = new InProcessEventBus();
        facade = new DbConfigFacade(new JdbcParamConfRepository(dataSource), eventBus);
    }

    @Test
    void seedConfigsAreLoadedOnStartup() {
        // 迁移器 V1 中 seed 入了 5 项
        assertThat(facade.get("game.level.rate", String.class)).contains("1.0");
        assertThat(facade.get("world.greetings", String.class)).contains("欢迎来到 twinkle");
        assertThat(facade.currentVersion()).isEqualTo(0L); // 初始版本
    }

    @Test
    void upsertTriggersEventBusBroadcastAndCacheUpdate() {
        AtomicInteger eventCount = new AtomicInteger();
        eventBus.subscribe("config-center", ConfigChangeEvent.class, event -> eventCount.incrementAndGet());

        // 业务方订阅外部变更（收到事件后可以重读 rate）
        AtomicInteger businessReadCount = new AtomicInteger();
        eventBus.subscribe("config-center", ConfigChangeEvent.class,
                event -> businessReadCount.incrementAndGet());

        facade.upsert("game.level.rate", "2.5");

        // 1. 自身重读（cache 已更新）
        assertThat(facade.get("game.level.rate", String.class)).contains("2.5");
        // 2. 业务订阅者收到事件
        assertThat(eventCount.get()).isEqualTo(1);
        assertThat(businessReadCount.get()).isEqualTo(1);
        // 3. 版本号递增
        assertThat(facade.currentVersion()).isEqualTo(1L);
    }

    @Test
    void typeConversion_handlesLongIntegerBooleanDouble() {
        facade.upsert("test.long", "123");
        facade.upsert("test.int", "42");
        facade.upsert("test.bool", "true");
        facade.upsert("test.bool_false", "0");
        facade.upsert("test.double", "3.14");

        assertThat(facade.get("test.long", Long.class)).contains(123L);
        assertThat(facade.get("test.int", Integer.class)).contains(42);
        assertThat(facade.get("test.bool", Boolean.class)).contains(true);
        assertThat(facade.get("test.bool_false", Boolean.class)).contains(false);
        assertThat(facade.get("test.double", Double.class)).contains(3.14);
    }

    @Test
    void getOrDefault_returnsDefaultWhenMissing() {
        Optional<String> missing = facade.get("nonexistent.key", String.class);
        assertThat(missing).isEmpty();
        assertThat(facade.getOrDefault("nonexistent.key", "fallback")).isEqualTo("fallback");
    }

    @Test
    void migrationVersionTablePersists() {
        // 同一 Database 第二次跑迁移应该是 no-op
        int secondRun = org.gms.data.migrate.MigrationRunner.applyMigrations(dataSource, "sqlite");
        assertThat(secondRun).isZero();
        // 数据仍然存在
        assertThat(facade.get("game.exp.rate", String.class)).contains("1.0");
    }
}
