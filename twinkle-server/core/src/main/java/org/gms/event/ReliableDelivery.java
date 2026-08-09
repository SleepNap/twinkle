package org.gms.event;

/**
 * 可靠投递扩展接口（架构 4.5：跨进程携带单一属主序号）。
 *
 * <p>{@link ReliableEventBus} 的 delegate 若实现本接口，投递时携带 {@code streamId/seq/messageId}
 * ——接收侧（目标进程）据此经 {@link ReliableReceiver} 做 bus_stream 持久化去重 + ack 闭环。
 * 进程内实现（InProcessEventBus）也实现本接口（序号经 outbox 行传递，接收侧读 bus_stream 判序）。
 *
 * <p>放 core（稳定底座）：ReliableEventBus 依赖接口而非具体实现（铁律 1，不假设进程内）。
 */
public interface ReliableDelivery {

    /**
     * 可靠投递：携带单一属主序号 + 全局消息 id。
     *
     * @param streamId  逻辑流（单一属主序号归属）
     * @param seq       流内序号
     * @param messageId 全局唯一消息 id（幂等去重 / ack 依据）
     * @param target    投递目标（逻辑名）
     * @param payload   负载
     */
    <T> void sendReliable(String streamId, long seq, String messageId, String target, T payload);
}
