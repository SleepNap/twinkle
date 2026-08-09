package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.channel.ChannelMapManager;
import org.gms.channel.MonsterReassignTickHandler;
import org.gms.channel.MonsterSpawnService;
import org.gms.domain.game.lease.DefaultControllerLeaseService;
import org.gms.domain.game.lease.ControllerLeaseService;
import org.gms.role.ChannelProcessCondition;
import org.gms.tick.TickScheduler;

/**
 * 怪物控制租约装配（事故报告阶段 B：租约巡检 + 无主怪重新分配，都挂既有游戏 tick）。
 *
 * <p>租约服务放 domain-game 稳定层（可替换 handler 换代不重置租约）；巡检经
 * {@link DefaultControllerLeaseService} 实现 TickHandler，无主怪重分配经
 * {@link MonsterReassignTickHandler}——均不新增线程（2C2G 红线 15）。
 *
 * <p>启动期 {@code @Context} registrar 把两个 TickHandler 注册进 tickScheduler 并 start()
 * （GameTickLoop.start() 幂等，与 PersistConfig 的 registrar 共存安全）。
 *
 * <p>租约是频道进程专属（split 下 coordinator 管理进程不装配，架构 4.2）。
 */
@Factory
@Requires(condition = ChannelProcessCondition.class)
public class LeaseConfig {

    @Bean
    @Singleton
    public ControllerLeaseService controllerLeaseService(
            @Property(name = "twinkle.lease.ttlSeconds", defaultValue = "50") long ttlSeconds,
            @Property(name = "twinkle.lease.cooldownSeconds", defaultValue = "15") long cooldownSeconds,
            @Property(name = "twinkle.lease.sweepIntervalMs", defaultValue = "10000") long sweepIntervalMs) {
        return new DefaultControllerLeaseService(ttlSeconds, cooldownSeconds, sweepIntervalMs);
    }

    @Bean
    @Singleton
    public MonsterReassignTickHandler monsterReassignTickHandler(ChannelMapManager mapManager,
                                                                 MonsterSpawnService spawnService) {
        return new MonsterReassignTickHandler(mapManager, spawnService);
    }

    /** 启动期注册租约巡检 + 无主怪重分配两个 tick handler 并启动 tick 循环。 */
    @Bean
    @Context
    @Singleton
    public LeaseTickRegistrar leaseTickRegistrar(TickScheduler tickScheduler,
                                                 ControllerLeaseService controllerLeaseService,
                                                 MonsterReassignTickHandler reassignHandler) {
        return new LeaseTickRegistrar(tickScheduler, controllerLeaseService, reassignHandler);
    }

    /** 启动装配：注册两个 TickHandler → 启动 tick 循环（幂等）。 */
    @Singleton
    public static final class LeaseTickRegistrar {
        LeaseTickRegistrar(TickScheduler tickScheduler, ControllerLeaseService controllerLeaseService,
                           MonsterReassignTickHandler reassignHandler) {
            if (controllerLeaseService instanceof DefaultControllerLeaseService dcl) {
                tickScheduler.register(dcl);
            }
            tickScheduler.register(reassignHandler);
            tickScheduler.start();
        }
    }
}
