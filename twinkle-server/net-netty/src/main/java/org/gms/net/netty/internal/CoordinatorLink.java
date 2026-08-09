package org.gms.net.netty.internal;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * coordinator 连接封装（架构 4.5：频道/管理进程 → coordinator 的 TCP 长连接生命周期）。
 *
 * <p>统一管理 {@link InternalClient} 的连接/重连/连接持有：
 * <ul>
 *   <li>连接建立（含重连）→ 通知全部监听器（上层重放 REGISTER 上报 / 重挂帧处理器）。</li>
 *   <li>{@link #connection()} 返回当前连接（未连接/断开为 null，调用方降级）。</li>
 *   <li>断链重连由 {@link InternalClient} 后台循环，coordinator 无状态重启后自动重建
 *       （架构 4.2）。</li>
 * </ul>
 */
public final class CoordinatorLink implements AutoCloseable {

    private static final Logger LOG = LogManager.getLogger(CoordinatorLink.class);

    private final InternalClient client;
    private final List<Consumer<InternalConnection>> connectListeners = new CopyOnWriteArrayList<>();

    private volatile InternalConnection connection;

    public CoordinatorLink(InetSocketAddress coordinatorAddress, long reconnectDelayMillis) {
        this.client = new InternalClient(coordinatorAddress, conn -> {
            this.connection = conn;
            connectListeners.forEach(l -> l.accept(conn));
        }, reconnectDelayMillis);
    }

    /** 启动连接（异步；失败后台重连）。 */
    public void start() {
        client.connect();
    }

    /** 注册连接建立（含重连）监听器——在 Netty IO 线程回调，须快速返回。 */
    public void addConnectListener(Consumer<InternalConnection> listener) {
        connectListeners.add(listener);
        InternalConnection conn = connection;
        if (conn != null) {
            listener.accept(conn);
        }
    }

    /** 当前连接（未连接/断开为 null）。 */
    public InternalConnection connection() {
        return connection;
    }

    /** 下一帧消息 ID（RPC 用）；未连接返回 0（调用方应降级）。 */
    public long nextMessageId() {
        InternalConnection conn = connection;
        return conn == null ? 0 : conn.nextMessageId();
    }

    public boolean isConnected() {
        return connection != null && connection.isActive();
    }

    @Override
    public void close() {
        client.close();
    }
}
