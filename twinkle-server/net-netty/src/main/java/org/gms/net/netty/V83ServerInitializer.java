package org.gms.net.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.gms.net.encryption.CipherPair;
import org.gms.net.packet.HandlerRegistry;

/**
 * v83 客户端连接管道装配（架构 net-netty）。
 *
 * <p>每连接：独立 {@link CipherPair}（收发 IV，握手下发）→ hello 明文 → 加密编解码 →
 * {@link NetworkSession} 分发。心跳超时 30s 断链。
 */
public final class V83ServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final int IDLE_SECONDS = 30;

    private final HandlerRegistry registry;

    public V83ServerInitializer(HandlerRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        CipherPair ciphers = new CipherPair(NetworkSession.MAPLE_VERSION);
        ChannelPipeline p = ch.pipeline();
        p.addLast("idle", new IdleStateHandler(0, 0, IDLE_SECONDS));
        p.addLast("decoder", new V83PacketDecoder(ciphers.receive()));
        p.addLast("encoder", new V83PacketEncoder(ciphers.send()));
        p.addLast("session", new NetworkSession(registry, ciphers));
    }
}
