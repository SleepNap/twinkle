package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;

/**
 * 共享底座服务装配（架构 4.1：管理进程与频道进程都需要的 core 服务）。
 *
 * <p>VersionGate / EntityReloadCoordinator / EntityReloadService 是 core 热重载基建，
 * 两进程都需要：频道进程用于游戏逻辑换代，管理进程的运维 API（/admin/v1/reload/*）
 * 也引用它们。拆到共享装配（而非 ChannelConfig 内），使 split 下 coordinator 进程
 * 也能解析 http-api 控制器的依赖。
 */
@Factory
public class SharedServiceConfig {

    @Bean
    @Singleton
    public VersionGate versionGate() {
        return new DefaultVersionGate();
    }

    @Bean
    @Singleton
    public EntityReloadCoordinator entityReloadCoordinator() {
        return new EntityReloadCoordinator();
    }

    @Bean
    @Singleton
    public EntityReloadService entityReloadService(EntityReloadCoordinator coordinator, VersionGate versionGate) {
        return new EntityReloadService(coordinator, versionGate);
    }
}
