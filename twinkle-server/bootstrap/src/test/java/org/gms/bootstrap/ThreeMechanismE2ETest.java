package org.gms.bootstrap;

import org.gms.coordinator.CoordinatorService;
import org.gms.coordinator.LocationTable;
import org.gms.coordinator.ChannelRegistry;
import org.gms.coordinator.SingleOwnerStore;
import org.gms.channel.ChannelLocationBinder;
import org.gms.channel.ChannelMessageSubscriber;
import org.gms.channel.ChannelPacketFactory;
import org.gms.channel.PlayerSessionRegistry;
import org.gms.channel.WhisperHandler;
import org.gms.event.InProcessEventBus;
import org.gms.message.MessageTargets;
import org.gms.message.NoticeMessage;
import org.gms.message.WhisperRequest;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.service.admin.OnlinePlayerEvents;
import org.gms.service.intercoord.IntercoordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三机制玩法端到端（架构 4.4 消息总线 + 定位表：悄悄话投递、公告广播、定位更新）。
 *
 * <p>单进程模拟：ChannelMessageSubscriber 订阅本频道 target；WhisperHandler 经定位表 +
 * 总线投递。用内存 FakeSession（记录收到的包）验证。
 */
class ThreeMechanismE2ETest {

    private InProcessEventBus eventBus;
    private IntercoordService intercoord;
    private PlayerSessionRegistry sessions;
    private ChannelMessageSubscriber subscriber;

    /** 内存会话：记录收到的 OutPacket 字节。 */
    static final class FakeSession implements PacketSession {
        final List<byte[]> received = new ArrayList<>();
        SessionStage stage = SessionStage.IN_GAME;
        private final long id;

        FakeSession() {
            this.id = FakeSessionId.incrementAndGet();
        }

        @Override
        public void send(OutPacket packet) {
            received.add(packet.getBytes());
        }

        @Override
        public void close(String reason) {
        }

        @Override
        public SessionStage stage() {
            return stage;
        }

        @Override
        public void transition(SessionStage next) {
            stage = next;
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

    /** 会话 id 分配（FakeSession 模拟 NetworkSession 的全局单调 sessionId）。 */
    private static final java.util.concurrent.atomic.AtomicLong FakeSessionId = new java.util.concurrent.atomic.AtomicLong(8000);

    @BeforeEach
    void setUp() {
        eventBus = new InProcessEventBus();
        intercoord = new CoordinatorService(new LocationTable(), new ChannelRegistry(), new SingleOwnerStore());
        sessions = new PlayerSessionRegistry();
        subscriber = new ChannelMessageSubscriber(1, intercoord, sessions, eventBus);
        new ChannelLocationBinder(1, intercoord, eventBus); // 进图/下线事件 → 定位表
    }

    @Test
    void whisperDeliveredToTargetSession() {
        // 定位：发送者 1001、接收者 1002 都在频道 1
        intercoord.registerPlayer(1001, 1);
        intercoord.registerPlayer(1002, 1);
        FakeSession target = new FakeSession();
        sessions.claim(1002, target);

        WhisperRequest req = new WhisperRequest(1001, "Alice", 1002, "Bob", "你好");
        eventBus.send(MessageTargets.channel(1), req); // 跨频道投递（模拟总线送达）

        assertThat(target.received).hasSize(1);
        byte[] pkt = target.received.get(0);
        // WHISPER opcode (0x87) 小端
        assertThat((pkt[0] & 0xFF) | ((pkt[1] & 0xFF) << 8)).isEqualTo(SendOpcode.WHISPER.getValue());
    }

    @Test
    void whisperToOfflineChannelIgnored() {
        intercoord.registerPlayer(1001, 1);
        // 1002 不在线（定位表无记录）
        FakeSession target = new FakeSession();
        sessions.claim(1002, target);

        // 定位不在本频道 → 投递被拒
        WhisperRequest req = new WhisperRequest(1001, "Alice", 1002, "Bob", "hi");
        eventBus.send(MessageTargets.channel(1), req);
        assertThat(target.received).isEmpty();
    }

    @Test
    void noticeBroadcastToAllOnlineSessions() {
        intercoord.registerPlayer(1001, 1);
        intercoord.registerPlayer(1002, 1);
        FakeSession s1 = new FakeSession();
        FakeSession s2 = new FakeSession();
        sessions.claim(1001, s1);
        sessions.claim(1002, s2);

        eventBus.send(MessageTargets.channel(1), new NoticeMessage(0, "欢迎光临", 0));

        assertThat(s1.received).hasSize(1);
        assertThat(s2.received).hasSize(1);
        // SERVERMESSAGE (0x44)
        assertThat((s1.received.get(0)[0] & 0xFF) | ((s1.received.get(0)[1] & 0xFF) << 8))
                .isEqualTo(SendOpcode.SERVERMESSAGE.getValue());
    }

    @Test
    void locationTableUpdatedOnLoginAndOfflineEvents() {
        // 进图事件 → 定位表登记
        eventBus.send(OnlinePlayerEvents.TARGET,
                new OnlinePlayerEvents.PlayerOnline(1001, "Alice", 100000000, 10, 0));
        assertThat(intercoord.locate(1001)).contains(1);

        // 下线事件 → 定位表注销
        eventBus.send(OnlinePlayerEvents.TARGET, new OnlinePlayerEvents.PlayerOffline(1001));
        assertThat(intercoord.locate(1001)).isEmpty();
    }
}
