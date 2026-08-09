package org.gms.event;

import org.gms.event.OutboxRepository.OutboxRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可靠接收方测试（架构 4.5 恰好一次的接收侧：bus_stream 持久化去重 + ack 闭环）。
 *
 * <p>验证：
 * <ul>
 *   <li>按序应用一次（seq 单调）。</li>
 *   <li>越序暂存等待前序（不丢弃）。</li>
 *   <li>重复投递（重投）幂等去重，不重复应用。</li>
 *   <li>重启后从 bus_stream 恢复去重状态（不重复应用已投递）。</li>
 * </ul>
 */
class ReliableReceiverTest {

    /** 内存 outbox（含 bus_stream 序号）。 */
    static final class MemoryOutbox implements OutboxRepository {
        final ConcurrentMap<String, Long> streamLastSeq = new ConcurrentHashMap<>();
        final ConcurrentMap<String, Boolean> acked = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong();

        @Override
        public long insert(OutboxRow row) {
            return idGen.incrementAndGet();
        }

        @Override
        public List<OutboxRow> findPending() {
            return List.of();
        }

        @Override
        public void markDelivered(long id) {
        }

        @Override
        public void markAcked(String messageId) {
            acked.put(messageId, Boolean.TRUE);
        }

        @Override
        public long lastDeliveredSeq(String streamId) {
            return streamLastSeq.getOrDefault(streamId, 0L);
        }

        @Override
        public void advanceLastDeliveredSeq(String streamId, long seq) {
            streamLastSeq.merge(streamId, seq, Math::max);
        }
    }

    @Test
    void orderedAppliedOnce() {
        MemoryOutbox outbox = new MemoryOutbox();
        ReliableReceiver receiver = new ReliableReceiver(outbox);
        List<String> applied = new ArrayList<>();

        receiver.deliver("cc:player:1", "cc:player:1:1", 1, "m1", applied::add);
        receiver.deliver("cc:player:1", "cc:player:1:2", 2, "m2", applied::add);

        assertThat(applied).containsExactly("m1", "m2");
        assertThat(outbox.acked).containsKeys("cc:player:1:1", "cc:player:1:2");
        assertThat(outbox.lastDeliveredSeq("cc:player:1")).isEqualTo(2);
    }

    @Test
    void outOfOrderBufferedUntilPredecessor() {
        MemoryOutbox outbox = new MemoryOutbox();
        ReliableReceiver receiver = new ReliableReceiver(outbox);
        List<String> applied = new ArrayList<>();

        // 越序投 seq=2 → 暂存
        receiver.deliver("s", "s:2", 2, "m2", applied::add);
        assertThat(applied).isEmpty();

        // 前序 seq=1 到 → 应用 1，随后释放 2
        receiver.deliver("s", "s:1", 1, "m1", applied::add);
        assertThat(applied).containsExactly("m1", "m2");
        assertThat(outbox.lastDeliveredSeq("s")).isEqualTo(2);
    }

    @Test
    void duplicateDeliveryNotAppliedTwice() {
        MemoryOutbox outbox = new MemoryOutbox();
        ReliableReceiver receiver = new ReliableReceiver(outbox);
        List<String> applied = new ArrayList<>();

        receiver.deliver("s", "s:1", 1, "m1", applied::add);
        // 重投同消息（进程崩了重发）
        receiver.deliver("s", "s:1", 1, "m1", applied::add);

        assertThat(applied).containsExactly("m1"); // 不重复应用
    }

    @Test
    void restartRestoresDedupStateFromBusStream() {
        MemoryOutbox outbox = new MemoryOutbox();
        ReliableReceiver first = new ReliableReceiver(outbox);
        first.deliver("s", "s:1", 1, "m1", x -> {
        });
        first.deliver("s", "s:2", 2, "m2", x -> {
        });

        // 重启：新 ReliableReceiver 从 outbox（bus_stream）恢复 lastDeliveredSeq
        ReliableReceiver restarted = new ReliableReceiver(outbox);
        List<String> applied = new ArrayList<>();
        // 重投 m1/m2（已应用过）→ 不重复应用
        restarted.deliver("s", "s:1", 1, "m1", applied::add);
        restarted.deliver("s", "s:2", 2, "m2", applied::add);
        assertThat(applied).isEmpty(); // 去重状态恢复，重投不重复应用
    }
}
