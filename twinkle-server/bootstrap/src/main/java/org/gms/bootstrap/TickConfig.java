package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.gms.tick.GameTickLoop;
import org.gms.tick.TickScheduler;

/**
 * 游戏 tick 循环装配（架构 5.1：游戏 tick 单线程，换点干净——L3 热重载安全点）。
 *
 * <p>M4 生产启用：默认 100ms 间隔（{@code twinkle.tick.interval}）。承载：
 * <ul>
 *   <li>L4 增量 FLUSH（CharacterFlushTickHandler 注册进来，每 N tick 刷脏角色，红线 17）。</li>
 *   <li>插件 tick 贡献点宿主（插件 TickHandler 注册进来）。</li>
 * </ul>
 * GameTickLoop 线程为 daemon、不阻塞 JVM 退出；启动在装配层显式 {@link TickScheduler#start()}。
 */
@Factory
public class TickConfig {

    @Bean
    @Singleton
    public TickScheduler tickScheduler(
            @Property(name = "twinkle.tick.interval", defaultValue = "100") long intervalMillis) {
        return new GameTickLoop(intervalMillis);
    }
}
