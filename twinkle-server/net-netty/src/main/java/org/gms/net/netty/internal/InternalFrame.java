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
     * 负载按 UTF-8 解码（JSON/文本负载读取，内部帧协议负载均为 UTF-8 JSON）。
     */
    default String payloadText() {
        return new String(payload(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 消息类型（架构 4.5 内部通信：帧头携带类型，路由/分发用）。
     *
     * <ul>
     *   <li>{@link #RPC}：请求-响应（频道侧 IntercoordService 方法调用 → coordinator 真值）。</li>
     *   <li>{@link #RPC_RESPONSE}：RPC 响应（消息ID 关联请求，CompletableFuture 匹配）。</li>
     *   <li>{@link #EVENT}：事件投递（悄悄话/公告等，走消息总线）。</li>
     *   <li>{@link #REGISTER}：频道启动上报（注册中心，携带频道ID+host:port）。</li>
     *   <li>{@link #HEARTBEAT}：心跳（channel → coordinator 续期）。</li>
     *   <li>{@link #LOCATE}：定位查询（已并入 RPC，保留枚举值兼容）。</li>
     * </ul>
     */
    public enum MessageType {
        RPC,
        RPC_RESPONSE,
        EVENT,
        REGISTER,
        HEARTBEAT,
        LOCATE
    }
}
