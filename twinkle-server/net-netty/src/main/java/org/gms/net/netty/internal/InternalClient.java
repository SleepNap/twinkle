package org.gms.net.netty.internal;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 内部通信客户端（架构 4.5：channel → coordinator 主动 TCP 长连接）。
 *
 * <p>与 {@link InternalServer} 配套：连接 coordinator 内部端口，装配同一套帧编解码，经
 * {@link InternalConnection} 暴露。支持<b>断线重连</b>（架构 4.2：coordinator 无状态重启 →
 * 频道重连重新上报 → 定位表自动重建），重连成功回调让上层重放注册/订阅。
 *
 * <p>生命周期：{@link #connect} 建立连接（重连在后台循环），{@link #close} 停止。
 */
public final class InternalClient implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(InternalClient.class);

    private final EventLoopGroup workerGroup = new NioEventLoopGroup(1);
    private final InetSocketAddress coordinatorAddress;
    private final Consumer<InternalConnection> connectionHandler;
    private final long reconnectDelayMillis;

    private volatile InternalConnection connection;
    private volatile boolean running;
    private final java.util.concurrent.atomic.AtomicBoolean reconnectScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * @param coordinatorAddress coordinator 内部端口（host:port）。
     * @param connectionHandler  连接建立（含重连）回调：上层在此重放注册/心跳/RPC。
     * @param reconnectDelayMillis 断线重连间隔。
     */
    public InternalClient(InetSocketAddress coordinatorAddress, Consumer<InternalConnection> connectionHandler,
                          long reconnectDelayMillis) {
        this.coordinatorAddress = coordinatorAddress;
        this.connectionHandler = connectionHandler;
        this.reconnectDelayMillis = reconnectDelayMillis;
    }

    /** 建立首次连接（异步；失败则后台定时重连）。 */
    public void connect() {
        running = true;
        doConnect();
    }

    /** 当前连接（未连接/断开为 null）。 */
    public InternalConnection connection() {
        return connection;
    }

    private void doConnect() {
        if (!running) {
            return;
        }
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("internal-decoder", new InternalFrameDecoder());
                        ch.pipeline().addLast("internal-encoder", new InternalFrameEncoder());
                    }
                });
        bootstrap.connect(coordinatorAddress).addListener(future -> {
            if (future.isSuccess()) {
                Channel ch = ((io.netty.channel.ChannelFuture) future).channel();
                // 数组持有者：断链回调引用的 conn 在赋值后才能取到（lambda 不能引用未定赋局部变量）
                final InternalConnection[] holder = new InternalConnection[1];
                holder[0] = InternalConnection.attach(ch, () -> onDisconnect(holder[0]));
                this.connection = holder[0];
                reconnectScheduled.set(false);
                LOG.info("内部通信已连接 coordinator: {}", coordinatorAddress);
                connectionHandler.accept(holder[0]);
            } else {                LOG.warn("内部通信连接 coordinator 失败: {}（{}），{}ms 后重试",
                        coordinatorAddress, future.cause().getMessage(), reconnectDelayMillis);
                scheduleReconnect();
            }
        });
    }

    /** 断链（当前连接的 close 回调）：仅当仍是当前连接才清空并调度重连（防旧连接迟到断链误清新连接）。 */
    private void onDisconnect(InternalConnection conn) {
        if (conn != null && conn != this.connection) {
            return; // 旧连接迟到断链，当前已有新连接，不处理
        }
        this.connection = null;
        scheduleReconnect();
    }

    /** 调度一次重连（原子防重复调度）。 */
    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        if (reconnectScheduled.compareAndSet(false, true)) {
            LOG.info("内部通信重连 coordinator（{}ms 后）", reconnectDelayMillis);
            workerGroup.schedule(this::doConnect, reconnectDelayMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void close() {
        running = false;
        InternalConnection conn = connection;
        if (conn != null) {
            conn.close();
        }
        workerGroup.shutdownGracefully().syncUninterruptibly();
        LOG.info("内部通信客户端已停止");
    }
}
