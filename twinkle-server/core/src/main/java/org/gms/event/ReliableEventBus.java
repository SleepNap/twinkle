package org.gms.event;

import org.gms.message.MessageTargets;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 可靠事件总线（架构 4.5 可靠性三件套：持久化队列 + 幂等去重 + 单一属主序号 = 恰好一次）。
 *
 * <p>装饰 {@link EventBus}（进程内投递），发送侧先落 outbox（PENDING）→ 投递 → 标记投递。
 * 核心保证：
 * <ul>
 *   <li><b>持久化队列</b>：发消息先落 outbox（投递成功才 ack，进程崩了重发未 ACKED）。</li>
 *   <li><b>幂等去重</b>：每条消息带全局 {@code messageId}；接收方按消息 id 去重（已处理不重复应用）。</li>
 *   <li><b>单一属主序号</b>：同一逻辑流（streamId）消息带单调 {@code seq}；接收方按 stream 的
 *       {@code lastDeliveredSeq} 按序投递、越序丢弃（防乱序重复）。</li>
 * </ul>
 * 三者组合 = 语义上的恰好一次——CC 迁移不掉数据、不重复的核心（架构 4.7）。
 *
 * <p>M4 单进程内：outbox 持久化（重启重投）+ 内存去重/序号。M6 跨进程时接收侧去重状态落
 * {@code bus_stream} 表、投递走网络帧，接口不变（铁律 1）。
 *
 * <p>线程安全：发送可并发；接收侧同步派发（复用 InProcessEventBus 语义）。
 */
public final class ReliableEventBus {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger(ReliableEventBus.class);

    private final EventBus delegate;
    private final OutboxRepository outbox;
    private final PayloadCodec codec;

    /** 发送侧：每 stream 的下一序号（单一属主）。 */
    private final ConcurrentMap<String, AtomicLong> streamSeq = new ConcurrentHashMap<>();

    /** 接收侧：每 stream 已投递序号（幂等去重 + 按序）。 */
    private final ConcurrentMap<String, Long> deliveredSeq = new ConcurrentHashMap<>();

    /** 接收侧：已处理消息 id（幂等去重，避免重投重复应用）。 */
    private final ConcurrentMap<String, Boolean> processedMessageIds = new ConcurrentHashMap<>();

    public ReliableEventBus(EventBus delegate, OutboxRepository outbox) {
        this(delegate, outbox, PayloadCodec.MARKER);
    }

    public ReliableEventBus(EventBus delegate, OutboxRepository outbox, PayloadCodec codec) {
        this.delegate = delegate;
        this.outbox = outbox;
        this.codec = codec;
        // 启动重投：取出未 ACKED 的 in-flight 消息（进程崩了重发，架构 4.5）
        for (OutboxRepository.OutboxRow row : outbox.findPending()) {
            LOG.info("可靠总线启动重投: messageId={} stream={} seq={}", row.messageId(), row.streamId(), row.seq());
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

        // 1) 持久化队列：先落 outbox（PENDING），投递成功才 ack
        long id = outbox.insert(new OutboxRepository.OutboxRow(0L, stream, seq, messageId, target,
                message.getClass().getName(), codec.encode(message), OutboxRepository.OutboxRow.PENDING));

        // 2) 投递 + 标记（接收方按 stream seq 去重，重投不重复应用）
        deliver(new OutboxRepository.OutboxRow(id, stream, seq, messageId, target,
                message.getClass().getName(), codec.encode(message), OutboxRepository.OutboxRow.PENDING));
        return CompletableFuture.completedFuture(null);
    }

    /** 投递一条 outbox 消息到 delegate（接收侧去重 + 严格按序；package-private 供测试直接驱动重投）。 */
    void deliver(OutboxRepository.OutboxRow row) {
        // 单一属主序号：严格按序投递（该流只投 seq == last+1；越序/重复等待前序或已投）
        long last = deliveredSeq.getOrDefault(row.streamId(), 0L);
        if (row.seq() != last + 1) {
            LOG.info("可靠总线越序/重复丢弃: messageId={} seq={}（last={}）", row.messageId(), row.seq(), last);
            return;
        }
        // 幂等去重：同 messageId 已处理过则不重复应用
        if (processedMessageIds.putIfAbsent(row.messageId(), Boolean.TRUE) != null) {
            LOG.info("可靠总线幂等去重: messageId={} 已处理", row.messageId());
            return;
        }
        deliveredSeq.put(row.streamId(), row.seq());
        // 反序列化 + 投递
        Object payload = codec.decode(row.payload(), row.payloadType());
        if (payload == null) {
            LOG.error("可靠总线负载反序列化失败: messageId={} type={}", row.messageId(), row.payloadType());
            return;
        }
        try {
            delegate.send(row.target(), payload).join();
            outbox.markDelivered(row.id());
        } catch (RuntimeException e) {
            LOG.error("可靠总线投递失败: messageId={}", row.messageId(), e);
        }
    }

    /**
     * 订阅可靠消息（接收侧幂等去重：重投已处理消息不重复触发 handler）。
     */
    public <T> AutoCloseable subscribe(String target, Class<T> type, Consumer<T> handler) {
        return delegate.subscribe(target, type, handler);
    }
}
