package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.gms.coordinator.ChannelRegistry;
import org.gms.coordinator.CoordinatorService;
import org.gms.coordinator.LocationTable;
import org.gms.coordinator.SingleOwnerStore;
import org.gms.service.intercoord.IntercoordService;

/**
 * 频道间三机制装配（架构 4.4：单一属主 / 定位表 / 频道注册，coordinator 内建）。
 *
 * <p>single 档单进程内嵌：IntercoordService → CoordinatorService（进程内真值）。
 * M6 分布式时此装配换网络桩实现，频道侧调用面不变（接口先行）。
 */
@Factory
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
