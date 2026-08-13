package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.config.ConfigFacade;
import org.gms.dialect.DbDialectRegistry;

import javax.sql.DataSource;

/**
 * 启动期强制装配数据层（架构 M0 验收：能启动 + 连 SQLite + param_conf 热改）。
 *
 * <p>Micronaut 的 {@code @Factory} Bean 是懒加载的——启动时若无人 {@code getBean(DataSource)}，
 * 数据源、迁移、param_conf 加载都不会执行。本类以 {@code @Context} 在 context 启动时强制装配
 * （构造注入即依赖解析）：
 * <ol>
 *   <li>{@code getBean(DataSource)} → 触发 DataSourceFactory → 迁移 + SQLite PRAGMA。</li>
 *   <li>{@code getBean(ConfigFacade)} → 触发 DbConfigFacade → 加载 param_conf 到内存索引。</li>
 *   <li>{@code getBean(DbDialectRegistry)} → 校验方言装配完整。</li>
 * </ol>
 *
 * <p>这是"启动校验"的职责：任何数据层 Bean 缺失都会在此暴露（编译期/装配期），而不是运行期才炸。
 *
 * <p>M6 split：频道进程可能不启 HTTP（无 EmbeddedServer → 无 ServerStartupEvent），
 * 故用 {@code @Context} 而非事件驱动——DB 是两进程都要的公共底座（架构 4.1），
 * 不能等 HTTP 事件才初始化。
 */
@Context
@Log4j2
public final class DataLayerInitializer implements ApplicationEventListener<ServerStartupEvent> {



    private final DataSource dataSource;
    private final ConfigFacade configFacade;
    private final DbDialectRegistry dialectRegistry;

    public DataLayerInitializer(DataSource dataSource, ConfigFacade configFacade,
                                DbDialectRegistry dialectRegistry) {
        this.dataSource = dataSource;
        this.configFacade = configFacade;
        this.dialectRegistry = dialectRegistry;
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        // 构造函数参数注入即强制装配（Bean 实例化时依赖图解析）。
        log.info(I18n.message("log.bootstrap.data_layer_ready"),
                dialectRegistry.resolveByUrl("").id() == null ? "?" : currentDialectName(),
                configFacade.currentVersion());
    }

    private String currentDialectName() {
        // 从数据源无法直接取 URL；方言名称由注册表按配置提供。这里仅作日志用途。
        return dialectRegistry.resolveByUrl("jdbc:sqlite:placeholder").id().name();
    }
}
