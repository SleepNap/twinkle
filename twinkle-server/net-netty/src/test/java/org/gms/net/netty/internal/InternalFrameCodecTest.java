package org.gms.net.netty.internal;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内部通信帧编解码往返（架构 4.5：半包/粘包/非法帧/超长），经 EmbeddedChannel 走完整 pipeline。
 */
class InternalFrameCodecTest {

    @Test
    void 编解码往返() {
        DefaultInternalFrame frame = new DefaultInternalFrame(InternalFrame.MessageType.EVENT, 42L,
                "{\"hello\":\"世界\"}".getBytes(StandardCharsets.UTF_8));

        EmbeddedChannel ch = channel();
        ch.writeInbound(encode(frame));

        InternalFrame decoded = ch.readInbound();
        assertThat(decoded).isNotNull();
        assertThat(decoded.type()).isEqualTo(InternalFrame.MessageType.EVENT);
        assertThat(decoded.messageId()).isEqualTo(42L);
        assertThat(decoded.payload()).isEqualTo(frame.payload());
    }

    @Test
    void 半包等待() {
        DefaultInternalFrame frame = new DefaultInternalFrame(InternalFrame.MessageType.HEARTBEAT, 1L, "hb");
        ByteBuf wire = encode(frame);

        EmbeddedChannel ch = channel();
        // 只喂帧头前 10 字节 → 不产出帧
        ByteBuf partial = wire.slice(0, 10).copy();
        ch.writeInbound(partial);
        InternalFrame noneYet = ch.readInbound();
        assertThat(noneYet).isNull();

        // 喂剩余完整字节 → 完整出帧
        ch.writeInbound(wire.slice(10, wire.readableBytes() - 10).copy());
        InternalFrame decoded = ch.readInbound();        assertThat(decoded).isNotNull();
        assertThat(decoded.messageId()).isEqualTo(1L);
    }

    @Test
    void 粘包切分() {
        DefaultInternalFrame a = new DefaultInternalFrame(InternalFrame.MessageType.RPC, 1L, "a");
        DefaultInternalFrame b = new DefaultInternalFrame(InternalFrame.MessageType.EVENT, 2L, "bb");

        EmbeddedChannel ch = channel();
        CompositeByteBuf both = Unpooled.compositeBuffer();
        both.addComponent(true, encode(a));
        both.addComponent(true, encode(b));
        ch.writeInbound(both);

        InternalFrame first = ch.readInbound();
        InternalFrame second = ch.readInbound();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.messageId()).isEqualTo(1L);
        assertThat(first.type()).isEqualTo(InternalFrame.MessageType.RPC);
        assertThat(second.messageId()).isEqualTo(2L);
        assertThat(second.type()).isEqualTo(InternalFrame.MessageType.EVENT);
    }

    @Test
    void magic不符断开() {
        ByteBuf wire = Unpooled.buffer();
        wire.writeShort(0x0000); // 非法 magic
        wire.writeByte(0);
        wire.writeLong(1L);
        wire.writeInt(0);

        EmbeddedChannel ch = channel();
        ch.writeInbound(wire);
        InternalFrame leaked = ch.readInbound();
        assertThat(leaked).as("magic 不符不应产出帧").isNull();
        boolean open = ch.isOpen();
        assertThat(open).as("magic 不符应断开连接").isFalse();
    }

    @Test
    void 超长拒绝() {
        DefaultInternalFrame frame = new DefaultInternalFrame(InternalFrame.MessageType.EVENT, 1L,
                new byte[InternalFrameEncoder.MAX_PAYLOAD + 1]);
        EmbeddedChannel ch = channel();
        try {
            ch.writeInbound(encode(frame));
            throw new AssertionError("超长应抛异常");
        } catch (IllegalArgumentException expected) {
            // 期望超长拒绝（编码阶段抛出）
        }
    }

    private static EmbeddedChannel channel() {
        return new EmbeddedChannel(new InternalFrameDecoder());
    }

    /** 帧编码 → ByteBuf（wrapped 不拷贝）。 */
    private static ByteBuf encode(InternalFrame frame) {
        ByteBuf buf = Unpooled.buffer();
        new InternalFrameEncoder().encode(null, frame, buf);
        return buf;
    }
}
