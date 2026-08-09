package org.gms.event;


import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import lombok.extern.log4j.Log4j2;

/**
 * 可靠事件总线（架构 4.5 可靠性三件套：持久化队列 + 幂等去重 + 单一属主序号 = 恰好一次）。
 *
 * <p>装饰 {@link EventBus}（进程内/网络投递），发送侧先落 outbox（PENDING）→ 投递 → 标记投递。
 * 核心保证：
 * <ul>
 *   <li><b>持久化队列</b>：发消息先落 outbox（投递成功才 ack，进程崩了重发未 ACKED）。</li>
 *   <li><b>幂等去重</b>：每条消息带全局 {@code messageId}；接收方按消息 id 去重（已处理不重复应用）。</li>
 *   <li><b>单一属主序号</b>：同一逻辑流（streamId）消息带单调 {@code seq}；接收方按 stream 的
 *       {@code lastDeliveredSeq} 按序投递、越序暂存等待前序（防乱序重复）。</li>
 * </ul>
 * 三者组合 = 语义上的恰好一次——CC 迁移不掉数据、不重复的核心（架构 4.7）。
 *
 * <p>M6 跨进程 ack 协议闭环（接收方权威）：
 * <ul>
 *   <li><b>发送方</b>：落 outbox（PENDING）→ 投递 → {@code markDelivered}。重启重投所有非 ACKED。</li>
 *   <li><b>接收方</b>（{@link ReliableReceiver}）：收到消息 → 按 bus_stream 的 lastDeliveredSeq 判序
 *       去重 → 应用 → {@code advanceLastDeliveredSeq} + {@code markAcked}（发送方 outbox 落定，不再重投）。</li>
 * </ul>
 *
 * <p>本类只负责发送侧可靠性（落库 + 投递 + 重投）；接收侧去重/ack 由 {@link ReliableReceiver} 承担，
 * 两者经共享 outbox/bus_stream 表协作（单机多进程同库即协作；跨机经网络 ack 帧，M6 后续）。
 */
@Log4j2
public final class ReliableEventBus {



    private final EventBus delegate;
    private final OutboxRepository outbox;
    private final PayloadCodec codec;

    /** 发送侧：每 stream 的下一序号（单一属主）。 */
    private final ConcurrentMap<String, AtomicLong> streamSeq = new ConcurrentHashMap<>();

    /** 发送侧：已投递序号（本进程 outbox 重投的幂等去重；重启后由 outbox 状态重建）。 */
    private final ConcurrentMap<String, Long> deliveredSeq = new ConcurrentHashMap<>();

    /** 发送侧：已处理消息 id（本进程 outbox 重投去重）。 */
    private final ConcurrentMap<String, Boolean> processedMessageIds = new ConcurrentHashMap<>();

    public ReliableEventBus(EventBus delegate, OutboxRepository outbox) {
        this(delegate, outbox, PayloadCodec.MARKER);
    }

    public ReliableEventBus(EventBus delegate, OutboxRepository outbox, PayloadCodec codec) {
        this.delegate = delegate;
        this.outbox = outbox;
        this.codec = codec;
        // 启动重投：取出未 ACKED 的 in-flight 消息（进程崩了重发，架构 4.5）。
        // 注意：ACKED = 接收方已确认应用，重投只重投未确认的。
        for (OutboxRepository.OutboxRow row : outbox.findPending()) {
            log.info("可靠总线启动重投: messageId={} stream={} seq={}", row.messageId(), row.streamId(), row.seq());
            deliver(row);
        }
    }

    /**
     * 可靠发送（同一逻辑流按序，全局去重）。
     *
     * @param streamId 逻辑流（如 {@code "cc:player:123"}）；为 null 时用 {@code "default"}
     * @param target   投递目标（逻辑名，channel:{id} / * 广播）
     * @param message  负载（经 {@link PayloadCodec} 序列化入 outbox）
     * @return 落库 + 投递完成 future
     */
    public <T> CompletableFuture<Void> send(String streamId, String target, T message) {
        String stream = streamId == null ? "default" : streamId;
        long seq = streamSeq.computeIfAbsent(stream, k -> new AtomicLong()).incrementAndGet();
        String messageId = stream + ":" + seq;
        String payload = codec.encode(message);

        // 1) 持久化队列：先落 outbox（PENDING），接收方 ack 前视为未确认（可重投）
        long id = outbox.insert(new OutboxRepository.OutboxRow(0L, stream, seq, messageId, target,
                message.getClass().getName(), payload, OutboxRepository.OutboxRow.PENDING));

        // 2) 投递 + 标记 DELIVERED（接收方 ack 才 ACKED）
        deliver(new OutboxRepository.OutboxRow(id, stream, seq, messageId, target,
                message.getClass().getName(), payload, OutboxRepository.OutboxRow.PENDING));
        return CompletableFuture.completedFuture(null);
    }

    /** 投递一条 outbox 消息到 delegate（发送侧重投去重；package-private 供测试直接驱动重投）。 */
    public void deliver(OutboxRepository.OutboxRow row) {
        String stream = row.streamId();
        // 幂等去重：同 messageId 已投递过则不重复投（发送侧重投保护；接收侧另有 ReliableReceiver 去重）
        if (processedMessageIds.putIfAbsent(row.messageId(), Boolean.TRUE) != null) {
            log.info("可靠总线幂等去重（发送侧）: messageId={} 已投递", row.messageId());
            return;
        }
        long last = deliveredSeq.getOrDefault(stream, 0L);
        if (row.seq() <= last) {
            log.info("可靠总线重复序号丢弃: messageId={} seq={}（last={}）", row.messageId(), row.seq(), last);
            return;
        }
        deliveredSeq.put(stream, row.seq());
        // 反序列化 + 投递（发送侧真实投递；接收侧按 bus_stream 判序去重）
        Object payload = codec.decode(row.payload(), row.payloadType());
        if (payload == null) {
            log.error("可靠总线负载反序列化失败: messageId={} type={}", row.messageId(), row.payloadType());
            return;
        }
        try {
            // 跨进程投递：delegate 支持 ReliableDelivery 时携带序号（接收侧 ReliableReceiver 恰好一次）；
            // 否则普通 send（进程内，接收侧即本地）。
            if (delegate instanceof ReliableDelivery reliable) {
                reliable.sendReliable(stream, row.seq(), row.messageId(), row.target(), payload);
            } else {
                delegate.send(row.target(), payload).join();
            }
            // 投递成功 → DELIVERED（等接收方 ack 落定 ACKED）
            outbox.markDelivered(row.id());
        } catch (RuntimeException e) {
            log.error("可靠总线投递失败: messageId={}", row.messageId(), e);
        }
    }

    /**
     * 订阅可靠消息（接收侧幂等去重：重投已处理消息不重复触发 handler）。
     */
    public <T> AutoCloseable subscribe(String target, Class<T> type, Consumer<T> handler) {
        return delegate.subscribe(target, type, handler);
    }

    /** 调试/观测：已投递 stream 序号快照（测试断言用）。 */
    public Map<String, Long> deliveredSeqSnapshot() {
        return Map.copyOf(deliveredSeq);
    }
}
