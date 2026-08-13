package org.gms.bootstrap.plugin;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.LogicSystemRegistry;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.net.packet.HandlerRegistry;
import org.gms.observability.MdcKeys;
import org.gms.observability.Metrics;
import org.gms.observability.Sli;
import org.gms.plugin.PluginDescriptor;
import org.gms.plugin.runtime.PluginManager;
import org.gms.role.ChannelProcessCondition;
import org.gms.tick.TickScheduler;

import java.nio.file.Path;

/**
 * 启动期插件装配（架构 7.1：插件 = 热重载系统，启动 scan + load 全部插件）。
 *
 * <p>用 {@code @Context} + 构造注入强制启动装配（与 {@code NetworkServerInitializer} 一致）：
 * context 创建时即扫描插件目录并加载全部插件。插件目录不存在 = 无插件（可选组件，不阻断启动）。
 *
 * <p>可观测埋点（架构 12 / M4 可观测性纪律）：Metrics 计数 {@code plugin.loaded/unloaded/failed}。
 *
 * <p>插件宿主依赖 TickScheduler（频道进程专属，同 {@code PluginConfig}）。
 */
@Singleton
@Context
@Requires(condition = ChannelProcessCondition.class)
@Log4j2
public final class PluginInitializer {



    private final PluginManager pluginManager;
    private final Metrics metrics;

    public PluginInitializer(PluginManager pluginManager,
                             HandlerRegistry registry,
                             LogicSystemRegistry logicSystemRegistry,
                             TickScheduler tickScheduler,
                             VersionGate versionGate,
                             EntityReloadCoordinator entityReloadCoordinator,
                             Metrics metrics) {
        this.pluginManager = pluginManager;
        this.metrics = metrics;
        // 强制装配宿主所需注册表（构造注入即完成）；随后加载全部插件
        loadAll();
    }

    private void loadAll() {
        int loaded = 0;
        for (PluginDescriptor descriptor : pluginManager.scan()) {
            try {
                pluginManager.load(descriptor);
                loaded++;
                metrics.increment(Sli.PLUGIN_LOADED);
            } catch (PluginManager.PluginLoadException e) {
                log.error(I18n.message("log.plugin.startup_load_failed"), descriptor.id(), e.getMessage());
                metrics.increment(Sli.PLUGIN_FAILED);
            }
        }
        if (loaded > 0) {
            log.info(I18n.message("log.plugin.startup_loaded"), loaded);
        } else {
            log.info(I18n.message("log.plugin.startup_no_plugins"));
        }
    }
}
