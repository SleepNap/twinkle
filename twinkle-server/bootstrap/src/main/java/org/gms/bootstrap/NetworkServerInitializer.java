package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.login.handler.LoginHandlerRegistrar;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;

/**
 * 启动期网络装配（架构 bootstrap：Netty 登录服 + 登录 handler 注册，M1 链路）。
 *
 * <p>用 {@code @Context} + 构造注入强制启动装配（与 {@link DataLayerInitializer}
 * 一致）：context 创建时即注册 login 模块 handler 并启动 {@link LoginServer}，
 * 不依赖 {@code ServerStartupEvent}（无 HTTP server 时该事件不发布）。
 *
 * <p>HTTP 与游戏 Netty 隔离 EventLoop（红线 4）：LoginServer 用独立
 * NioEventLoopGroup，不共享 Micronaut HTTP 的 EventLoop。
 */
@Singleton
@Context
public final class NetworkServerInitializer {

    private static final Logger LOG = LogManager.getLogger(NetworkServerInitializer.class);

    /** 单进程自连频道服地址（M6 分布式按配置取）。 */
    private static final byte[] CHANNEL_IP = new byte[]{127, 0, 0, 1};

    public NetworkServerInitializer(HandlerRegistry registry,
                                    LoginHandlerRegistrar loginHandlers,
                                    LoginServer loginServer,
                                    @Property(name = "twinkle.net.login.port", defaultValue = "8484") int port,
                                    @Property(name = "twinkle.server.name", defaultValue = "twinkle") String serverName,
                                    @Property(name = "twinkle.net.channel.port", defaultValue = "8484") int channelPort) {
        loginHandlers.register(registry, serverName, CHANNEL_IP, channelPort);
        LOG.info("登录 handler 注册完成，共 {} 个贡献点", registry.registeredCount());
        loginServer.start(port);
    }
}
