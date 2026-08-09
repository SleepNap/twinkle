package org.gms.event;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import lombok.extern.log4j.Log4j2;

/**
 * 可靠消息接收方（架构 4.5 恰好一次的接收侧：bus_stream 持久化去重 + ack 闭环）。
 *
 * <p>与 {@link ReliableEventBus}（发送侧）配对的接收侧组件：订阅某 target 的可靠消息流，
 * 按 {@code bus_stream} 表（经 {@link OutboxRepository}）维护的 {@code lastDeliveredSeq}
 * 判序 + 去重，应用后推进序号并 ack（发送方 outbox ACKED，不再重投）。
 *
 * <p>语义：同一逻辑流（streamId）的消息严格按序应用一次；越序暂存等待前序（不丢弃）；
 * 重复投递（进程崩了重投）按序号/消息 id 幂等去重——恰好一次的核心。
 *
 * <p>使用场景：CC 迁移目标频道订阅 {@code ChangeChannelRequest} 流（{@code cc:player:{id}}），
 * 重启重投不重复迁移玩家。
 */
@Log4j2
public final class ReliableReceiver {



    private final OutboxRepository outbox;

    /** 每 stream 已投递序号（内存镜像；真值在 bus_stream 表，跨进程重启不丢）。 */
    private final ConcurrentMap<String, Long> deliveredSeq = new ConcurrentHashMap<>();

    /** 每 stream 暂存的越序消息（等待前序到齐）。 */
    private final ConcurrentMap<String, Map<Long, BufferedMessage>> buffered = new ConcurrentHashMap<>();

    public ReliableReceiver(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    /** 暂存的越序消息（含 messageId，ack 落定用）。 */
    private record BufferedMessage(String messageId, Object message) {
    }

    /**
     * 尝试投递一条可靠消息：按 stream 序号判序 → 应用 → 推进 bus_stream + ack。
     *
     * <p>调用方（订阅者处理器）把收到的消息交给本方法，本方法负责恰好一次的判定；
     * 判定通过才调用 {@code applier}（幂等业务应用），并在应用后落定序号与 ack。
     *
     * @param streamId  逻辑流（如 {@code "cc:player:123"}）
     * @param messageId 全局消息 id（outbox ack 依据）
     * @param seq       流内序号
     * @param message   负载（应用对象）
     * @param applier   业务应用回调（须幂等——重投时判定去重保证只调一次）
     */
    public <T> void deliver(String streamId, String messageId, long seq, T message, Consumer<T> applier) {
        String stream = streamId == null ? "default" : streamId;
        synchronized (this) {
            long last = deliveredSeq.computeIfAbsent(stream, k -> outbox.lastDeliveredSeq(stream));
            if (seq <= last) {
                // 已应用过（重复投递/重投）——幂等去重，直接 ack 落定
                log.info("可靠接收方重复投递丢弃: messageId={} seq={}（last={}）", messageId, seq, last);
                outbox.markAcked(messageId);
                return;
            }
            if (seq != last + 1) {
                // 越序：暂存等待前序
                log.info("可靠接收方越序暂存: messageId={} seq={}（last={}）", messageId, seq, last);
                buffered.computeIfAbsent(stream, k -> new ConcurrentHashMap<>())
                        .put(seq, new BufferedMessage(messageId, message));
                return;
            }
            // 按序应用（恰好一次）
            apply(stream, messageId, seq, message, applier);
            // 前序到齐，释放暂存（递归按序）
            flushBuffered(stream, applier);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void apply(String stream, String messageId, long seq, T message, Consumer<T> applier) {
        try {
            applier.accept(message);
        } catch (RuntimeException e) {
            log.error("可靠接收方应用失败: messageId={} seq={}", messageId, seq, e);
            return;
        }
        deliveredSeq.put(stream, seq);
        outbox.advanceLastDeliveredSeq(stream, seq);
        outbox.markAcked(messageId);
        log.debug("可靠接收方应用完成: stream={} seq={} messageId={}", stream, seq, messageId);
    }

    @SuppressWarnings("unchecked")
    private <T> void flushBuffered(String stream, Consumer<T> applier) {
        Map<Long, BufferedMessage> pending = buffered.get(stream);
        if (pending == null) {
            return;
        }
        long last = deliveredSeq.getOrDefault(stream, outbox.lastDeliveredSeq(stream));
        BufferedMessage next = pending.remove(last + 1);
        if (next != null) {
            apply(stream, next.messageId(), last + 1, (T) next.message(), applier);
            flushBuffered(stream, applier);
        }
    }

    /** 调试/观测：已投递 stream 序号快照。 */
    public Map<String, Long> deliveredSeqSnapshot() {
        return Map.copyOf(deliveredSeq);
    }
}
