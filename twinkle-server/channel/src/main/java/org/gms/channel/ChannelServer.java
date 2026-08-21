package org.gms.channel;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.net.netty.V83ServerInitializer;
import org.gms.net.netty.DisconnectListener;
import org.gms.net.netty.HeartbeatConfig;
import org.gms.net.packet.HandlerRegistry;

import java.net.InetSocketAddress;

/**
 * 频道服 Netty 服务端（架构 M2 进图：客户端选角后重连频道服）。
 *
 * <p>复用 {@link V83ServerInitializer}（v83 加密管道 + NetworkSession 分发），
 * 独立 EventLoop（红线 4）。2C2G 预算：boss 1 + worker 2，与登录服一致。
 *
 * <p>生命周期：{@link #start} 绑定端口（v83 客户端经典频道端口 8484），
 * {@link #close} 优雅关闭。bootstrap 装配时调用。
 */
@Log4j2
public final class ChannelServer implements AutoCloseable {


    private final HandlerRegistry registry;
    private final DisconnectListener disconnectListener;
    private final HeartbeatConfig heartbeatConfig;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public ChannelServer(HandlerRegistry registry) {
        this(registry, null, HeartbeatConfig.defaults());
    }

    public ChannelServer(HandlerRegistry registry, DisconnectListener disconnectListener) {
        this(registry, disconnectListener, HeartbeatConfig.defaults());
    }

    public ChannelServer(HandlerRegistry registry, DisconnectListener disconnectListener,
                         HeartbeatConfig heartbeatConfig) {
        this.registry = registry;
        this.disconnectListener = disconnectListener;
        this.heartbeatConfig = heartbeatConfig;
    }

    /** 启动并绑定端口。 */
    public void start(int port) {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new V83ServerInitializer(registry, disconnectListener, heartbeatConfig));
        serverChannel = bootstrap.bind(port).syncUninterruptibly().channel();
    }

    public int boundPort() {
        return serverChannel == null ? -1 : ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    @Override
    public void close() {
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
        log.info(I18n.message("log.channel.server.stopped"));
    }
}
