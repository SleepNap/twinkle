package org.gms.event;

import org.gms.event.OutboxRepository.OutboxRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可靠总线测试（架构 4.5 三件套：持久化队列 + 幂等去重 + 单一属主序号 = 恰好一次）。
 *
 * <p>用内存 OutboxRepository + 记录派发的 InProcessEventBus 验证：
 * <ul>
 *   <li>发送先落 outbox（PENDING），投递后标记。</li>
 *   <li>同一逻辑流按序投递（seq 单调）。</li>
 *   <li>重复投递同 messageId 不重复应用（幂等去重）。</li>
 *   <li>重启重投未 ACKED 消息（进程崩了重发）。</li>
 * </ul>
 */
class ReliableEventBusTest {

    /** 内存 outbox（记录落库行 + ack 状态）。 */
    static final class MemoryOutbox implements OutboxRepository {
        final List<OutboxRow> rows = new ArrayList<>();
        final ConcurrentMap<String, Boolean> acked = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public long insert(OutboxRow row) {
            OutboxRow stored = new OutboxRow(idGen.incrementAndGet(), row.streamId(), row.seq(),
                    row.messageId(), row.target(), row.payloadType(), row.payload(), OutboxRow.PENDING);
            rows.add(stored);
            return stored.id();
        }

        @Override
        public List<OutboxRow> findPending() {
            return rows.stream()
                    .filter(r -> !OutboxRow.ACKED.equals(r.status()))
                    .toList();
        }

        @Override
        public void markDelivered(long id) {
            replaceStatus(id, OutboxRow.DELIVERED);
        }

        @Override
        public void markAcked(String messageId) {
            acked.put(messageId, Boolean.TRUE);
        }

        private void replaceStatus(long id, String status) {
            for (int i = 0; i < rows.size(); i++) {
                OutboxRow r = rows.get(i);
                if (r.id() == id) {
                    rows.set(i, new OutboxRow(id, r.streamId(), r.seq(), r.messageId(),
                            r.target(), r.payloadType(), r.payload(), status));
                    return;
                }
            }
        }
    }

    /** 记录派发的内存 EventBus（替代 InProcessEventBus 避免依赖核心类）。 */
    static final class RecordingBus implements EventBus {
        final List<Object> delivered = new ArrayList<>();

        @Override
        public <T> java.util.concurrent.CompletableFuture<Void> send(String target, T payload) {
            delivered.add(payload);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> AutoCloseable subscribe(String target, Class<T> type, Consumer<T> handler) {
            return () -> {
            };
        }
    }

    /** 直通 codec：真实对象原样进出（测试可靠性语义用，替代默认 MARKER）。 */
    static final class PassThroughCodec implements PayloadCodec {
        @Override
        public String encode(Object payload) {
            return String.valueOf(payload);
        }

        @Override
        public Object decode(String payload, String payloadType) {
            return payload; // 测试直接用字符串负载
        }
    }

    private ReliableEventBus newBus(MemoryOutbox outbox, RecordingBus bus) {
        return new ReliableEventBus(bus, outbox, new PassThroughCodec());
    }

    @Test
    void sendPersistsToOutboxAndDelivers() {
        MemoryOutbox outbox = new MemoryOutbox();
        RecordingBus bus = new RecordingBus();
        ReliableEventBus reliable = newBus(outbox, bus);

        reliable.send("cc:player:1", "*", new String("hello"));

        assertThat(outbox.rows).hasSize(1);
        assertThat(outbox.rows.get(0).status()).isEqualTo(OutboxRow.DELIVERED); // 投递后标记
        assertThat(bus.delivered).hasSize(1);
    }

    @Test
    void sameStreamOrderedSequentially() {
        MemoryOutbox outbox = new MemoryOutbox();
        RecordingBus bus = new RecordingBus();
        ReliableEventBus reliable = newBus(outbox, bus);

        reliable.send("stream-A", "*", "m1");
        reliable.send("stream-A", "*", "m2");
        reliable.send("stream-A", "*", "m3");

        assertThat(outbox.rows).hasSize(3);
        assertThat(outbox.rows.get(0).seq()).isEqualTo(1);
        assertThat(outbox.rows.get(1).seq()).isEqualTo(2);
        assertThat(outbox.rows.get(2).seq()).isEqualTo(3);
        assertThat(bus.delivered).containsExactly("m1", "m2", "m3");
    }

    @Test
    void outOfOrderDeliveryDroppedBySeq() {
        MemoryOutbox outbox = new MemoryOutbox();
        RecordingBus bus = new RecordingBus();
        ReliableEventBus reliable = newBus(outbox, bus);

        // 先投 seq=3（越序），应被丢弃
        reliable.deliver(new OutboxRow(9, "s", 3, "s:3", "*", "String.class", "x", OutboxRow.PENDING));
        assertThat(bus.delivered).isEmpty();
        assertThat(outbox.rows).isEmpty(); // 未落库的投递不记录

        // 再按序投 seq=1,2,3 → 全送达
        reliable.deliver(new OutboxRow(9, "s", 1, "s:1", "*", "String.class", "x", OutboxRow.PENDING));
        reliable.deliver(new OutboxRow(9, "s", 2, "s:2", "*", "String.class", "x", OutboxRow.PENDING));
        reliable.deliver(new OutboxRow(9, "s", 3, "s:3", "*", "String.class", "x", OutboxRow.PENDING));
        assertThat(bus.delivered).hasSize(3);
    }

    @Test
    void duplicateMessageIdNotAppliedTwice() {
        MemoryOutbox outbox = new MemoryOutbox();
        RecordingBus bus = new RecordingBus();
        ReliableEventBus reliable = newBus(outbox, bus);

        reliable.deliver(new OutboxRow(1, "s", 1, "s:1", "*", "String.class", "x", OutboxRow.PENDING));
        reliable.deliver(new OutboxRow(2, "s", 2, "s:1", "*", "String.class", "x", OutboxRow.PENDING)); // 同 messageId

        assertThat(bus.delivered).hasSize(1); // 幂等去重
    }

    @Test
    void restartReprocessesUnackedMessages() {
        MemoryOutbox outbox = new MemoryOutbox();
        RecordingBus bus = new RecordingBus();
        ReliableEventBus reliable = newBus(outbox, bus);

        reliable.send("cc:player:9", "*", "before-crash");
        // 模拟进程崩溃：outbox 里有一条未 ACKED（DELIVERED 但未 ack）——重启重投
        // （M4 简化：DELIVERED 视为需重投，因为接收方未确认）
        int pendingBefore = outbox.findPending().size();
        assertThat(pendingBefore).isEqualTo(1);

        // 重启：新 ReliableEventBus 构造时重投 findPending()
        RecordingBus bus2 = new RecordingBus();
        ReliableEventBus restarted = newBus(outbox, bus2);
        assertThat(bus2.delivered).hasSize(1); // 重投送达
    }
}
