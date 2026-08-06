package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import org.gms.config.ConfigFacade;
import org.gms.dialect.DbDialect;
import org.gms.dialect.DbDialectRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M0 验收：应用上下文能启动 + 关键 Bean 装配正确（架构 M0 第 2 项 bootstrap 装配）。
 *
 * <p>验证：
 * <ol>
 *   <li>Micronaut ApplicationContext 能完整启动（DI 图无缺失 Bean）。</li>
 *   <li>配置门面 Bean 存在（param_conf 加载路径通了）。</li>
 *   <li>方言注册表能解析 SQLite（低配默认）。</li>
 * </ol>
 *
 * <p>用 {@code :memory:} SQLite 避免污染文件系统。
 */
class BootstrapContextTest {

    @Test
    void applicationContextStartsAndWiresCoreBeans() throws Exception {
        // 用文件版 SQLite（:memory: 的 "::" 会被 Micronaut 属性解析截断，文件版无此问题）。
        // 用系统临时目录的绝对路径，避免 surefire 工作目录差异。
        // ApplicationContext.run(Map) 才是"键值配置"入口；run(String...) 是 CLI 参数形式。
        String dbPath = java.nio.file.Files.createTempDirectory("twinkle-boot-test")
                .resolve("test.db").toString();
        try (ApplicationContext ctx = ApplicationContext.run(java.util.Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.profile", "single"))) {

            // 配置门面（param_conf 加载路径）——迁移在 DataSourceFactory 创建时已跑
            assertThat(ctx.containsBean(ConfigFacade.class)).isTrue();
            ConfigFacade config = ctx.getBean(ConfigFacade.class);
            // V1 seed 的配置项能被读到
            assertThat(config.get("game.level.rate", String.class)).contains("1.0");

            // 方言注册表（低配默认 SQLite）
            assertThat(ctx.containsBean(DbDialectRegistry.class)).isTrue();
            DbDialectRegistry registry = ctx.getBean(DbDialectRegistry.class);
            assertThat(registry.resolveByUrl("jdbc:sqlite:test.db").id())
                    .isEqualTo(DbDialect.DialectId.SQLITE);
        }
    }
}
