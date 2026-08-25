package org.gms.channel;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
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
    private volatile Channel serverChannel;
    private ChannelGroup clientChannels;

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
    public synchronized void start(int port) {
        if (isRunning()) {
            throw new IllegalStateException("ChannelServer is already running");
        }
        EventLoopGroup newBossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup newWorkerGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        ChannelGroup newClientChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(newBossGroup, newWorkerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new V83ServerInitializer(
                            registry, disconnectListener, heartbeatConfig, newClientChannels));
            Channel newServerChannel = bootstrap.bind(port).syncUninterruptibly().channel();
            bossGroup = newBossGroup;
            workerGroup = newWorkerGroup;
            clientChannels = newClientChannels;
            serverChannel = newServerChannel;
        } catch (RuntimeException e) {
            newClientChannels.close().syncUninterruptibly();
            newBossGroup.shutdownGracefully().syncUninterruptibly();
            newWorkerGroup.shutdownGracefully().syncUninterruptibly();
            throw e;
        }
    }

    public int boundPort() {
        Channel current = serverChannel;
        return current == null ? -1 : ((InetSocketAddress) current.localAddress()).getPort();
    }

    public boolean isRunning() {
        Channel current = serverChannel;
        return current != null && current.isActive();
    }

    /** 停止接入并关闭所有客户端连接；DisconnectListener 会完成注销与存档入队。 */
    public synchronized void stop() {
        boolean hadResources = serverChannel != null || clientChannels != null || bossGroup != null || workerGroup != null;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }
        if (clientChannels != null) {
            clientChannels.close().syncUninterruptibly();
            clientChannels = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if (hadResources) {
            log.info(I18n.message("log.channel.server.stopped"));
        }
    }

    @Override
    public void close() {
        stop();
    }
}
