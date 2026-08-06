package org.gms.net.netty.internal;

/**
 * 内部通信帧接口（架构 4.5：进程间自定义二进制帧，M6 分布式用）。
 *
 * <p>M1 只定义契约（接口先行、分布式后置，架构 4.4/4.5）：帧 = {@code [帧头 | 消息类型 |
 * 消息ID | 负载]}。帧头/序列化细节 M6 随网络总线落地，本接口约束消息的最小承载结构。
 *
 * <p>可靠性语义（架构 4.5）：帧只负责传输，不丢/不重/有序由业务层保证——持久化队列 +
 * 接收方幂等去重 + 单一属主序号。
 */
public interface InternalFrame {

    /**
     * 帧内消息类型（业务路由用；帧头/编解码细节 M6 定）。
     */
    MessageType type();

    /**
     * 全局唯一消息 ID（幂等去重 / ack 追踪，架构 4.5）。
     */
    long messageId();

    /**
     * 负载字节（序列化格式 M6 定）。
     */
    byte[] payload();

    /**
     * 消息类型占位（M6 细化，如 RPC 请求 / 事件投递 / 心跳 / 定位查询）。
     */
    enum MessageType {
        RPC,
        EVENT,
        HEARTBEAT,
        LOCATE
    }
}
