package org.gms.channel;

import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话注册表代际语义测试（事故报告 §七 完成标准 3：compare-and-remove，迟到断链不能
 * 误删新会话）。
 */
class PlayerSessionRegistryTest {

    /** 模拟 NetworkSession 的全局单调 sessionId。 */
    private final AtomicLong sessionIds = new AtomicLong(9000);

    private final PlayerSessionRegistry registry = new PlayerSessionRegistry();

    private FakeSession newSession() {
        return new FakeSession(sessionIds.incrementAndGet());
    }

    static final class FakeSession implements PacketSession {
        final long id;

        FakeSession(long id) {
            this.id = id;
        }

        @Override
        public void send(OutPacket packet) {
        }

        @Override
        public void close(String reason) {
        }

        @Override
        public SessionStage stage() {
            return SessionStage.IN_GAME;
        }

        @Override
        public void transition(SessionStage stage) {
        }

        @Override
        public <T> T getAttr(String key) {
            return null;
        }

        @Override
        public void setAttr(String key, Object value) {
        }

        @Override
        public long sessionId() {
            return id;
        }
    }

    @Test
    void claimGeneratesMonotonicGeneration() {
        FakeSession a = newSession();
        long gen1 = registry.claim(42L, a);
        long gen2 = registry.claim(42L, a);      // 同连接重复 claim（覆盖）
        assertThat(gen2).isGreaterThan(gen1);
        assertThat(registry.entry(42L)).hasValueSatisfying(e -> {
            assertThat(e.generation()).isEqualTo(gen2);
            assertThat(e.session()).isEqualTo(a);
        });
    }

    @Test
    void unregisterOnlyRemovesMatchingSession() {
        FakeSession a = newSession();
        FakeSession b = newSession();
        registry.claim(42L, a);
        registry.claim(42L, b);                   // B 认领同角色 → 新代际覆盖

        // 旧连接 A 迟到关闭：unregister 不匹配 → false，且不删除 B 的登记
        assertThat(registry.unregister(42L, a)).isFalse();
        assertThat(registry.get(42L)).isEqualTo(b);
        assertThat(registry.supersededCleanupRejectedCount()).isEqualTo(1);

        // B 正常注销 → true
        assertThat(registry.unregister(42L, b)).isTrue();
        assertThat(registry.get(42L)).isNull();
    }

    @Test
    void unregisterMatchingSessionRemoves() {
        FakeSession a = newSession();
        registry.claim(42L, a);
        assertThat(registry.unregister(42L, a)).isTrue();
        assertThat(registry.get(42L)).isNull();
        assertThat(registry.supersededCleanupRejectedCount()).isZero();
    }

    @Test
    void getReturnsCurrentSession() {
        FakeSession a = newSession();
        registry.claim(1L, a);
        assertThat(registry.get(1L)).isEqualTo(a);
        assertThat(registry.get(999L)).isNull();
    }
}
