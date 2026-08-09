package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.coordinator.ChannelRegistry;
import org.gms.coordinator.CoordinatorService;
import org.gms.coordinator.LocationTable;
import org.gms.coordinator.SingleOwnerStore;
import org.gms.role.ManagementProcessCondition;
import org.gms.service.intercoord.IntercoordService;

/**
 * 频道间三机制装配（架构 4.4：单一属主 / 定位表 / 频道注册，coordinator 内建）。
 *
 * <p>管理进程（single 全内嵌 / split 的 coordinator 角色）装配进程内真值：
 * IntercoordService → CoordinatorService。频道进程不装配本类（装配
 * {@code RemoteIntercoordService} 网络桩，见 SplitConfig）。
 */
@Factory
@Requires(condition = ManagementProcessCondition.class)
public class CoordinatorConfig {

    @Bean
    @Singleton
    public LocationTable locationTable() {
        return new LocationTable();
    }

    @Bean
    @Singleton
    public ChannelRegistry channelRegistry() {
        return new ChannelRegistry();
    }

    @Bean
    @Singleton
    public SingleOwnerStore singleOwnerStore() {
        return new SingleOwnerStore();
    }

    @Bean
    @Singleton
    public IntercoordService intercoordService(LocationTable locationTable, ChannelRegistry channelRegistry,
                                               SingleOwnerStore singleOwnerStore) {
        return new CoordinatorService(locationTable, channelRegistry, singleOwnerStore);
    }
}
