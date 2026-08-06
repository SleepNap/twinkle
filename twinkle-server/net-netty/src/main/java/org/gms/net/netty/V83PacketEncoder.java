package org.gms.net.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.gms.net.encryption.AesCipher;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketCodec;

/**
 * v83 发包编码器（架构 net-netty）。
 *
 * <p>把业务构造的 {@link OutPacket} 编码为线包字节（header + 加密负载）写出。
 */
public final class V83PacketEncoder extends MessageToByteEncoder<OutPacket> {

    private final AesCipher sendCipher;

    public V83PacketEncoder(AesCipher sendCipher) {
        this.sendCipher = sendCipher;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, OutPacket msg, ByteBuf out) {
        out.writeBytes(PacketCodec.encodePacket(sendCipher, msg.getBytes()));
    }
}
