package org.gms.net.netty.internal;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.extern.log4j.Log4j2;

/**
 * 内部通信服务端（架构 4.5：coordinator 端监听，接收各进程 TCP 长连接）。
 *
 * <p>与 {@link LoginServer}/{@link ChannelServer} 同构：独立 EventLoop（红线 4），boss 1 +
 * worker 2。每连接装配 {@link InternalFrameDecoder}/{@link InternalFrameEncoder}，经
 * {@link InternalConnection} 暴露给上层（注册/路由/RPC）。
 *
 * <p>生命周期：{@link #start} 绑定端口（0 = 动态分配，测试用），{@link #close} 优雅关闭。
 */
@Log4j2
public final class InternalServer implements AutoCloseable {



    private final Consumer<InternalConnection> connectionHandler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final AtomicInteger connectionCount = new AtomicInteger();

    /**
     * @param connectionHandler 每连接建立后回调（coordinator 注册到连接注册表）。
     */
    public InternalServer(Consumer<InternalConnection> connectionHandler) {
        this.connectionHandler = connectionHandler;
    }

    public void start(int port) {
        bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("internal-decoder", new InternalFrameDecoder());
                        ch.pipeline().addLast("internal-encoder", new InternalFrameEncoder());
                        InternalConnection conn = InternalConnection.attach(ch,
                                () -> connectionCount.decrementAndGet());
                        connectionCount.incrementAndGet();
                        connectionHandler.accept(conn);
                    }
                });
        serverChannel = bootstrap.bind(port).syncUninterruptibly().channel();
        log.info("内部通信服务端启动，监听端口: {}", port);
    }

    public int boundPort() {
        return serverChannel == null ? -1 : ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** 当前已连接数（观测用）。 */
    public int connectionCount() {
        return connectionCount.get();
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
        log.info("内部通信服务端已停止");
    }
}
