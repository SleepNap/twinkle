package org.gms.net.netty.internal;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * 内部通信帧解码器（架构 4.5：进程间自定义二进制帧线格式）。
 *
 * <p>对应 {@link InternalFrameEncoder} 的线格式（大端）：
 * {@code [magic 2B 0x5457 | type 1B | messageId 8B | payloadLen 4B | payload]}。
 * 处理 TCP 流累积拆包（半包等待、粘包切分）；magic 不符或超长直接断开（防串包/恶意帧）。
 */
public final class InternalFrameDecoder extends ByteToMessageDecoder {

    private static final Logger LOG = LogManager.getLogger(InternalFrameDecoder.class);

    /** 帧头固定长度：magic(2) + type(1) + messageId(8) + payloadLen(4)。 */
    private static final int HEADER_LEN = 15;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 半包：帧头未到齐，等待
        if (in.readableBytes() < HEADER_LEN) {
            return;
        }
        in.markReaderIndex();
        short magic = in.readShort();
        if (magic != InternalFrameEncoder.MAGIC) {
            LOG.warn("内部帧 magic 不符: {}，断开连接", Integer.toHexString(magic));
            ctx.close();
            return;
        }
        int typeOrdinal = in.readUnsignedByte();
        long messageId = in.readLong();
        int payloadLen = in.readInt();
        if (payloadLen < 0 || payloadLen > InternalFrameEncoder.MAX_PAYLOAD) {
            LOG.warn("内部帧负载长度非法: {}，断开连接", payloadLen);
            ctx.close();
            return;
        }
        // 负载未到齐：回退读指针，等待
        if (in.readableBytes() < payloadLen) {
            in.resetReaderIndex();
            return;
        }
        InternalFrame.MessageType type = fromOrdinal(typeOrdinal);
        if (type == null) {
            LOG.warn("内部帧类型非法: {}", typeOrdinal);
            ctx.close();
            return;
        }
        byte[] payload = new byte[payloadLen];
        in.readBytes(payload);
        out.add(new DefaultInternalFrame(type, messageId, payload));
    }

    private static InternalFrame.MessageType fromOrdinal(int ordinal) {
        InternalFrame.MessageType[] values = InternalFrame.MessageType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : null;
    }
}
