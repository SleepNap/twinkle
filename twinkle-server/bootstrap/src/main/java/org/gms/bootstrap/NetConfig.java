package org.gms.bootstrap;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.gms.net.netty.HeartbeatConfig;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;
import org.gms.role.ManagementProcessCondition;

/**
 * 网络装配（架构 net-netty：HandlerRegistry 单例 + 登录服生命周期 + 心跳参数）。
 *
 * <p>HandlerRegistry 是贡献点注册表（红线 13），全进程单例（两进程都要——频道进程
 * 也用它注册游戏 handler）；HeartbeatConfig 两侧同用。LoginServer 仅管理进程（登录服，
 * split 下频道进程不启登录服）。
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

    /** 登录服仅管理进程装配（single 档全内嵌；split 下频道进程不启登录服）。
     *  @Bean(preDestroy="close")：context close 时释放端口（测试多 context 不残留）。 */
    @Bean(preDestroy = "close")
    @Singleton
    @Requires(condition = ManagementProcessCondition.class)
    public LoginServer loginServer(HandlerRegistry registry, HeartbeatConfig heartbeatConfig) {
        return new LoginServer(registry, heartbeatConfig);
    }
}
