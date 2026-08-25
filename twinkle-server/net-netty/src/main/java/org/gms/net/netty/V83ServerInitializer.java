package org.gms.net.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.gms.net.encryption.CipherPair;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.PacketSession;

import java.util.concurrent.TimeUnit;

/**
 * v83 客户端连接管道装配（架构 net-netty）。
 *
 * <p>每连接：独立 {@link CipherPair}（收发 IV，握手下发）→ hello 明文 → 加密编解码 →
 * {@link NetworkSession} 分发。心跳为 readerIdle 驱动（事故报告阶段 B：分阶段
 * PING/PONG 替代 allIdle——探测超时才关闭，连接存活由 NetworkSession 处理）。
 */
public final class V83ServerInitializer extends ChannelInitializer<SocketChannel> {

    private final HandlerRegistry registry;
    private final DisconnectListener disconnectListener;
    private final HeartbeatConfig heartbeatConfig;
    private final ChannelGroup clientChannels;

    public V83ServerInitializer(HandlerRegistry registry) {
        this(registry, null, HeartbeatConfig.defaults(), null);
    }

    public V83ServerInitializer(HandlerRegistry registry, DisconnectListener disconnectListener) {
        this(registry, disconnectListener, HeartbeatConfig.defaults(), null);
    }

    public V83ServerInitializer(HandlerRegistry registry, DisconnectListener disconnectListener,
                                HeartbeatConfig heartbeatConfig) {
        this(registry, disconnectListener, heartbeatConfig, null);
    }

    public V83ServerInitializer(HandlerRegistry registry, DisconnectListener disconnectListener,
                                HeartbeatConfig heartbeatConfig, ChannelGroup clientChannels) {
        this.registry = registry;
        this.disconnectListener = disconnectListener;
        this.heartbeatConfig = heartbeatConfig;
        this.clientChannels = clientChannels;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        if (clientChannels != null) {
            clientChannels.add(ch);
        }
        CipherPair ciphers = new CipherPair(PacketSession.MAPLE_VERSION);
        ChannelPipeline p = ch.pipeline();
        // 仅 readerIdle 驱动探测（不再用 allIdle——allIdle 会被服务端持续发包重置，永不触发）
        p.addLast("idle", new IdleStateHandler(heartbeatConfig.readerIdleMillis(), 0, 0, TimeUnit.MILLISECONDS));
        p.addLast("decoder", new V83PacketDecoder(ciphers.receive()));
        p.addLast("encoder", new V83PacketEncoder(ciphers.send()));
        p.addLast("session", new NetworkSession(registry, ciphers, disconnectListener, heartbeatConfig));
    }
}
