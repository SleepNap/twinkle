package org.gms.net.netty.internal;

import java.nio.charset.StandardCharsets;

/**
 * 内部通信帧具体实现（架构 4.5：进程间自定义二进制帧）。
 *
 * <p>帧承载 {@link InternalFrame} 契约：{@code [帧头 | 消息类型 | 消息ID | 负载]}。
 * 线格式（大端）由 {@link InternalFrameEncoder}/{@link InternalFrameDecoder} 定义：
 * {@code [magic 2B | type 1B | messageId 8B | payloadLen 4B | payload]}。
 * 本类持有内存形态：type + messageId + payload 字节（序列化格式由编解码器约定）。
 *
 * <p>可靠性语义（架构 4.5）：帧只负责传输，不丢/不重/有序由业务层保证——持久化队列 +
 * 接收方幂等去重 + 单一属主序号。
 */
public final class DefaultInternalFrame implements InternalFrame {

    private final MessageType type;
    private final long messageId;
    private final byte[] payload;

    public DefaultInternalFrame(MessageType type, long messageId, byte[] payload) {
        this.type = type;
        this.messageId = messageId;
        this.payload = payload == null ? new byte[0] : payload;
    }

    /** 便捷构造：UTF-8 负载。 */
    public DefaultInternalFrame(MessageType type, long messageId, String payloadText) {
        this(type, messageId, payloadText.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public MessageType type() {
        return type;
    }

    @Override
    public long messageId() {
        return messageId;
    }

    @Override
    public byte[] payload() {
        return payload;
    }

    /** 负载按 UTF-8 解码（用于 JSON/文本负载）。 */
    public String payloadText() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "InternalFrame{type=" + type + ", messageId=" + messageId + ", payloadLen=" + payload.length + '}';
    }
}
