package org.gms.bootstrap.plugin;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.gms.event.EventBus;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.LogicSystemRegistry;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.net.packet.HandlerRegistry;
import org.gms.plugin.runtime.ContributionRouter;
import org.gms.plugin.runtime.PluginManager;
import org.gms.tick.TickScheduler;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 插件系统装配（架构 7.1：插件系统 = 热重载系统，可装卸 + classloader 隔离）。
 *
 * <p>{@code twinkle.plugin.path} 指定插件目录（默认 {@code ./plugins}），目录不存在 = 无插件
 * （可选组件，不阻断启动）。PluginManager 持有宿主贡献点路由（{@link TwinklePluginHost}），
 * 插件经接口访问宿主服务（信任边界 = 全权但经接口）。
 */
@Factory
public class PluginConfig {

    @Bean
    @Singleton
    public LogicSystemRegistry logicSystemRegistry() {
        return new LogicSystemRegistry();
    }

    @Bean
    @Singleton
    public PluginManager pluginManager(
            @Property(name = "twinkle.plugin.path", defaultValue = "./plugins") String pluginPath,
            HandlerRegistry registry,
            LogicSystemRegistry logicSystemRegistry,
            TickScheduler tickScheduler,
            EventBus eventBus,
            VersionGate versionGate,
            EntityReloadCoordinator entityReloadCoordinator,
            EntityReloadService entityReloadService) {
        Path dir = Path.of(pluginPath);
        TwinklePluginHost host = new TwinklePluginHost(registry, logicSystemRegistry, tickScheduler,
                eventBus, versionGate, entityReloadCoordinator);
        // 命令式贡献点路由：register → host.registerCommand；subscribe → host.subscribeCommand
        ContributionRouter router = new ContributionRouter() {
            @Override
            public <T> org.gms.plugin.ContributionHandle register(String contributionType, T contribution, int version) {
                return host.registerCommand(contributionType, contribution, version);
            }

            @Override
            public <T> org.gms.plugin.ContributionHandle subscribe(String target, Class<T> eventType, Consumer<T> consumer) {
                return host.subscribeCommand(target, eventType, consumer);
            }
        };
        // 宿主服务解析：M4 插件可访问的宿主服务为空（信任边界接口，后续按需开放 AdminService 等）
        Function<Class<?>, Object> serviceResolver = type -> null;
        // 注入版本门 + 按实体渐进重载：插件 reload = L3 换代 + 中断在途（架构 5.3）
        return new PluginManager(dir, host, PluginManager.class.getClassLoader(), serviceResolver, router,
                versionGate, entityReloadService);
    }
}
