package org.gms.bootstrap;

import io.micronaut.context.ApplicationContext;
import org.gms.config.ConfigFacade;
import org.gms.dialect.DbDialect;
import org.gms.dialect.DbDialectRegistry;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;
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
 *   <li>M1：网络装配——HandlerRegistry 存在且登录 handler 已注册（4 个贡献点），
 *       LoginServer 已启动（随机端口避免占用）。</li>
 * </ol>
 *
 * <p>用文件版 SQLite 避免 {@code :memory:} 的 {@code ::} 被属性解析截断。
 */
class BootstrapContextTest {

    @Test
    void applicationContextStartsAndWiresCoreBeans() throws Exception {
        // 用系统临时目录的绝对路径，避免 surefire 工作目录差异。
        // ApplicationContext.run(Map) 才是"键值配置"入口；run(String...) 是 CLI 参数形式。
        String dbPath = java.nio.file.Files.createTempDirectory("twinkle-boot-test")
                .resolve("test.db").toString();
        // 脚本目录须存在（架构 6.4：twinkle.script.path 读不到启动即失败），用临时空目录
        String scriptDir = java.nio.file.Files.createTempDirectory("twinkle-script-test").toString();
        try (ApplicationContext ctx = ApplicationContext.run(java.util.Map.of(
                "twinkle.db.url", "jdbc:sqlite:" + dbPath,
                "twinkle.profile", "single",
                "twinkle.net.login.port", "0",
                "twinkle.net.channel.port", "0",
                "twinkle.script.path", scriptDir))) {

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

            // M1/M2：网络装配——handler 注册 + 登录服/频道服启动
            assertThat(ctx.containsBean(HandlerRegistry.class)).isTrue();
            HandlerRegistry handlers = ctx.getBean(HandlerRegistry.class);
            // 登录 12（密码/ToS/性别/服务器状态/HPMP警报/服务器列表/角色列表/查名/建角/查看所有/总览选角/选角）+
            // 频道 15（进图 2 + M3-5 游戏内 8 + M4 三机制 3 + 阶段 B MOVE_LIFE 1 + GENERAL_CHAT/值班 GM 1）
            assertThat(handlers.registeredCount()).isEqualTo(27);
            assertThat(ctx.containsBean(LoginServer.class)).isTrue();
            LoginServer loginServer = ctx.getBean(LoginServer.class);
            assertThat(loginServer.boundPort()).isGreaterThan(0);
        }
    }
}
