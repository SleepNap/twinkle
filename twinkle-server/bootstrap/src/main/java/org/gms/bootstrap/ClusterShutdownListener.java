package org.gms.bootstrap;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ShutdownEvent;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.role.ManagementProcessCondition;

/** 正常停止管理服务时先执行集群安全关闭，随后再让 Micronaut 销毁其余资源。 */
@Singleton
@Requires(condition = ManagementProcessCondition.class)
public final class ClusterShutdownListener implements ApplicationEventListener<ShutdownEvent> {

    private final DefaultClusterShutdownService shutdownService;

    public ClusterShutdownListener(DefaultClusterShutdownService shutdownService) {
        this.shutdownService = shutdownService;
    }

    @Override
    public void onApplicationEvent(ShutdownEvent event) {
        shutdownService.shutdownForContextClose();
    }
}
