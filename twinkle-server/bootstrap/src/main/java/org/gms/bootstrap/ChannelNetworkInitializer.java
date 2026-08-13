package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.channel.ChannelHandlerRegistrar;
import org.gms.channel.ChannelServer;
import org.gms.net.packet.HandlerRegistry;
import org.gms.role.ChannelProcessCondition;

/**
 * 启动期频道服装配（架构 M2 进图：客户端选角后重连频道服）。
 *
 * <p>与 {@link NetworkServerInitializer} 并列：登录服管选角前，频道服管进图。
 * 两者共用同一 {@link HandlerRegistry}（opcode 无冲突），各自独立 EventLoop
 * 启动（红线 4）。
 *
 * <p>频道服是频道进程专属（single 全内嵌；split 下仅 channel 角色装配）。
 */
@Singleton
@Context
@Requires(condition = ChannelProcessCondition.class)
@Log4j2
public final class ChannelNetworkInitializer {


    public ChannelNetworkInitializer(HandlerRegistry registry,
                                     ChannelHandlerRegistrar channelHandlers,
                                     ChannelServer channelServer,
                                     @Property(name = "twinkle.net.channel.port", defaultValue = "8584") int port) {
        channelHandlers.register(registry);
        log.info(I18n.message("log.bootstrap.channel_handlers_registered"), registry.registeredCount());
        channelServer.start(port);
    }
}
