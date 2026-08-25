package org.gms.net.netty;

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
import org.gms.net.packet.HandlerRegistry;

/**
 * 登录服 Netty 服务端（架构 net-netty / 红线 4：HTTP 与游戏 Netty 隔离 EventLoop）。
 *
 * <p>游戏 Netty 用<b>独立</b>的 {@link MultiThreadIoEventLoopGroup}（不共享 Micronaut HTTP 的
 * EventLoop），第三方 API 流量不挤占游戏连接线程。2C2G 预算（红线 15）：boss 1 线程、
 * worker 2 线程，登录服场景足够。
 *
 * <p>生命周期：{@link #start} 绑定端口，{@link #stop} 优雅关闭。bootstrap 装配时调用。
 */
@Log4j2
public final class LoginServer implements AutoCloseable {


    private final HandlerRegistry registry;
    private final HeartbeatConfig heartbeatConfig;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile Channel serverChannel;
    private ChannelGroup clientChannels;

    public LoginServer(HandlerRegistry registry) {
        this(registry, HeartbeatConfig.defaults());
    }

    public LoginServer(HandlerRegistry registry, HeartbeatConfig heartbeatConfig) {
        this.registry = registry;
        this.heartbeatConfig = heartbeatConfig;
    }

    /**
     * 启动并绑定端口。
     *
     * @param port 监听端口（v83 客户端经典登录端口 8484；0 = 动态分配，测试用）
     */
    public synchronized void start(int port) {
        if (isRunning()) {
            throw new IllegalStateException("LoginServer is already running");
        }
        EventLoopGroup newBossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup newWorkerGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        ChannelGroup newClientChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(newBossGroup, newWorkerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new V83ServerInitializer(registry, null, heartbeatConfig, newClientChannels));
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
        return current == null ? -1 : ((java.net.InetSocketAddress) current.localAddress()).getPort();
    }

    public boolean isRunning() {
        Channel current = serverChannel;
        return current != null && current.isActive();
    }

    /** 停止接入、关闭现有客户端连接并释放独立 EventLoop；可再次调用 {@link #start(int)}。 */
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
            log.info(I18n.message("log.login.server_stopped"));
        }
    }

    @Override
    public void close() {
        stop();
    }
}
