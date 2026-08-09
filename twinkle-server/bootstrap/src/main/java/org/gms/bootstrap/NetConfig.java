package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.gms.net.netty.HeartbeatConfig;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;

/**
 * 网络装配（架构 net-netty：HandlerRegistry 单例 + 登录服生命周期 + 心跳参数）。
 *
 * <p>HandlerRegistry 是贡献点注册表（红线 13），全进程单例；LoginServer 依赖它
 * 构造，登录 handler 在 {@link NetworkServerInitializer} 启动期注册。
 *
 * <p>心跳（事故报告阶段 B）：readerIdle 触发 PING 探测、pongTimeout 内无响应关闭。
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
    public HeartbeatConfig heartbeatConfig(
            @Property(name = "twinkle.net.heartbeat.readerIdleSeconds", defaultValue = "15") long readerIdleSeconds,
            @Property(name = "twinkle.net.heartbeat.pongTimeoutSeconds", defaultValue = "10") long pongTimeoutSeconds) {
        return new HeartbeatConfig(readerIdleSeconds * 1000, pongTimeoutSeconds * 1000);
    }

    @Bean
    @Singleton
    public LoginServer loginServer(HandlerRegistry registry, HeartbeatConfig heartbeatConfig) {
        return new LoginServer(registry, heartbeatConfig);
    }
}
