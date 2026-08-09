package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.event.EventBus;
import org.gms.event.OutboxRepository;
import org.gms.event.ReliableEventBus;
import org.gms.role.ChannelProcessCondition;

/**
 * 可靠总线装配（架构 4.5 可靠性三件套：持久化队列 + 幂等去重 + 单一属主序号 = 恰好一次）。
 *
 * <p>装饰进程内 {@link EventBus}，发送先落 outbox（bus_outbox 表，V5 迁移）→ 投递 → 标记。
 * CC 迁移请求走此总线（不掉数据、不重复的核心）。
 *
 * <p>CC 迁移是频道进程专属（single 全内嵌；split 下仅 channel 角色装配，管理进程不迁人）。
 */
@Factory
@Requires(condition = ChannelProcessCondition.class)
public class ReliableBusConfig {

    @Bean
    @Singleton
    public ReliableEventBus reliableEventBus(EventBus eventBus, OutboxRepository outboxRepository) {
        return new ReliableEventBus(eventBus, outboxRepository);
    }
}
