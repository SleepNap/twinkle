package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.channel.CharacterLoader;
import org.gms.channel.PlayerStorage;
import org.gms.channel.persist.CharacterFlushTickHandler;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.channel.persist.RestartService;
import org.gms.data.repo.CharacterSnapshotRepository;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.RestartCoordinator;
import org.gms.observability.Metrics;
import org.gms.role.ChannelProcessCondition;
import org.gms.tick.TickScheduler;

/**
 * 存档与重启装配（架构 5.4 L4：增量 FLUSH + DRAINING + 上下文恢复）。
 *
 * <p>CharacterSaveQueue 单写执行器（架构 6.2 ②）+ CharacterFlushTickHandler（定期增量 FLUSH，
 * 红线 17）+ RestartService（把 RestartCoordinator 状态机接到真实组件）。启动期注册 flush
 * handler 并启动 tick 循环（@Context 强制装配）。
 *
 * <p>存档是频道进程专属（split 下 coordinator 管理进程不装配，架构 4.2）。
 */
@Factory
@Requires(condition = ChannelProcessCondition.class)
public class PersistConfig {

    @Bean
    @Singleton
    public CharacterSaveQueue characterSaveQueue(CharacterSnapshotRepository snapshotRepository,
                                                 CharacterLoader loader,
                                                 PlayerStorage playerStorage) {
        return new CharacterSaveQueue(snapshotRepository, loader, playerStorage);
    }

    @Bean
    @Singleton
    public CharacterFlushTickHandler characterFlushTickHandler(CharacterSaveQueue saveQueue, Metrics metrics) {
        return new CharacterFlushTickHandler(saveQueue, metrics);
    }

    @Bean
    @Singleton
    public RestartCoordinator restartCoordinator() {
        return new RestartCoordinator();
    }

    @Bean
    @Singleton
    public RestartService restartService(RestartCoordinator coordinator, TickScheduler tickScheduler,
                                         EntityReloadService entityReloadService, CharacterSaveQueue saveQueue) {
        return new RestartService(coordinator, tickScheduler, entityReloadService, saveQueue);
    }

    /** 启动期注册 flush handler 并启动 tick 循环（@Context 强制装配，架构 5.1 游戏 tick 单线程）。 */
    @Bean
    @Context
    @Singleton
    public TickStartupRegistrar tickStartupRegistrar(TickScheduler tickScheduler,
                                                     CharacterFlushTickHandler flushHandler) {
        return new TickStartupRegistrar(tickScheduler, flushHandler);
    }

    /** 启动装配：注册 L4 增量 FLUSH handler → 启动 tick 循环。 */
    @Singleton
    public static final class TickStartupRegistrar {
        public TickStartupRegistrar(TickScheduler tickScheduler, CharacterFlushTickHandler flushHandler) {
            tickScheduler.register(flushHandler);
            tickScheduler.start();
        }
    }
}
