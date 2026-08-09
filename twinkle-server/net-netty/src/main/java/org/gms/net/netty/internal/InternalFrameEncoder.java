package org.gms.net.netty.internal;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * 内部通信帧编码器（架构 4.5：进程间自定义二进制帧线格式）。
 *
 * <p>线格式（大端）：{@code [magic 2B 0x5457 | type 1B | messageId 8B | payloadLen 4B | payload]}。
 * magic = {@code "TW"}（twinkle 内部通信标识，防串包）；type 枚举序值；payloadLen 为
 * payload 字节数（0 = 空负载，最大 {@value #MAX_PAYLOAD}）。
 */
public final class InternalFrameEncoder extends MessageToByteEncoder<InternalFrame> {

    /** 帧头 magic（ASCII "TW"）。 */
    public static final short MAGIC = 0x5457;

    /** 负载上限（64KB，防恶意超大帧）。 */
    public static final int MAX_PAYLOAD = 0x10000;

    @Override
    protected void encode(ChannelHandlerContext ctx, InternalFrame msg, ByteBuf out) {
        byte[] payload = msg.payload();
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("内部帧负载超限: " + payload.length + " > " + MAX_PAYLOAD);
        }
        out.writeShort(MAGIC);
        out.writeByte(msg.type().ordinal());
        out.writeLong(msg.messageId());
        out.writeInt(payload.length);
        out.writeBytes(payload);
    }
}
