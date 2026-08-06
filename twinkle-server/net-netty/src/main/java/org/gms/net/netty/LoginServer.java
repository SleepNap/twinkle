package org.gms.net.netty;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.net.packet.HandlerRegistry;

/**
 * 登录服 Netty 服务端（架构 net-netty / 红线 4：HTTP 与游戏 Netty 隔离 EventLoop）。
 *
 * <p>游戏 Netty 用<b>独立</b>的 {@link NioEventLoopGroup}（不共享 Micronaut HTTP 的
 * EventLoop），第三方 API 流量不挤占游戏连接线程。2C2G 预算（红线 15）：boss 1 线程、
 * worker 2 线程，登录服场景足够。
 *
 * <p>生命周期：{@link #start} 绑定端口，{@link #stop} 优雅关闭。bootstrap 装配时调用。
 */
public final class LoginServer implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(LoginServer.class);

    private final HandlerRegistry registry;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public LoginServer(HandlerRegistry registry) {
        this.registry = registry;
    }

    /**
     * 启动并绑定端口。
     *
     * @param port 监听端口（v83 客户端经典登录端口 8484）
     */
    public void start(int port) {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new V83ServerInitializer(registry));
        serverChannel = bootstrap.bind(port).syncUninterruptibly().channel();
        LOG.info("登录服启动，监听端口: {}", port);
    }

    public int boundPort() {
        return serverChannel == null ? -1 : ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
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
        LOG.info("登录服已停止");
    }
}
