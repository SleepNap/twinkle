package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.channel.ChannelHandlerRegistrar;
import org.gms.channel.ChannelServer;
import org.gms.net.packet.HandlerRegistry;

/**
 * 启动期频道服装配（架构 M2 进图：客户端选角后重连频道服）。
 *
 * <p>与 {@link NetworkServerInitializer} 并列：登录服管选角前，频道服管进图。
 * 两者共用同一 {@link HandlerRegistry}（opcode 无冲突），各自独立 EventLoop
 * 启动（红线 4）。
 */
@Singleton
@Context
public final class ChannelNetworkInitializer {

    private static final Logger LOG = LogManager.getLogger(ChannelNetworkInitializer.class);

    public ChannelNetworkInitializer(HandlerRegistry registry,
                                     ChannelHandlerRegistrar channelHandlers,
                                     ChannelServer channelServer,
                                     @Property(name = "twinkle.net.channel.port", defaultValue = "8584") int port) {
        channelHandlers.register(registry);
        LOG.info("频道 handler 注册完成，共 {} 个贡献点", registry.registeredCount());
        channelServer.start(port);
    }
}
