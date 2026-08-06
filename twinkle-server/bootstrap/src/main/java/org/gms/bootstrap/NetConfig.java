package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;

/**
 * 网络装配（架构 net-netty：HandlerRegistry 单例 + 登录服生命周期）。
 *
 * <p>HandlerRegistry 是贡献点注册表（红线 13），全进程单例；LoginServer 依赖它
 * 构造，登录 handler 在 {@link NetworkServerInitializer} 启动期注册。
 */
@Factory
public class NetConfig {

    @Bean
    @Singleton
    public HandlerRegistry handlerRegistry() {
        return new HandlerRegistry();
    }

    @Bean
    @Singleton
    public LoginServer loginServer(HandlerRegistry registry) {
        return new LoginServer(registry);
    }
}
