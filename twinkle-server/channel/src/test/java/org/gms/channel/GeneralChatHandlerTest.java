package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.i18n.I18n;
import org.gms.i18n.ResourceBundleI18nService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 玩家普通聊天协议测试。 */
class GeneralChatHandlerTest {

    @BeforeEach
    void setUp() {
        I18n.install(new ResourceBundleI18nService("zh-CN"));
    }

    @Test
    void chatTextPacketMatchesV83Layout() {
        ByteArrayInPacket packet = new ByteArrayInPacket(
                ChannelPacketFactory.chatText(7L, false, "你好", 1).getBytes());

        assertThat(packet.readUnsignedShort()).isEqualTo(SendOpcode.CHATTEXT.getValue());
        assertThat(packet.readInt()).isEqualTo(7);
        assertThat(packet.readByte()).isZero();
        assertThat(packet.readString()).isEqualTo("你好");
        assertThat(packet.readByte()).isEqualTo((byte) 1);
        assertThat(packet.available()).isZero();
    }

    private static InPacket input(String text, int show) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeString(text);
        packet.writeByte(show);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private static FakeSession sessionWithCharacter(long sessionId, long characterId, String name) {
        FakeSession session = new FakeSession(sessionId);
        Character character = new Character(1L);
        character.setId(characterId);
        character.setName(name);
        session.setAttr("character", character);
        return session;
    }

    private static final class FakeSession implements PacketSession {
        private final long sessionId;
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<OutPacket> sent = new ArrayList<>();
        private SessionStage stage = SessionStage.IN_GAME;

        private FakeSession(long sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void send(OutPacket packet) {
            sent.add(packet);
        }

        @Override
        public void close(String reason) {
            stage = SessionStage.HANDSHAKE;
        }

        @Override
        public SessionStage stage() {
            return stage;
        }

        @Override
        public void transition(SessionStage nextStage) {
            stage = nextStage;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T getAttr(String key) {
            return (T) attributes.get(key);
        }

        @Override
        public void setAttr(String key, Object value) {
            attributes.put(key, value);
        }

        @Override
        public long sessionId() {
            return sessionId;
        }

        private List<String> notices() {
            return sent.stream().map(OutPacket::getBytes).map(ByteArrayInPacket::new).map(packet -> {
                assertThat(packet.readUnsignedShort()).isEqualTo(SendOpcode.SERVERMESSAGE.getValue());
                assertThat(packet.readByte()).isEqualTo((byte) 4);
                return packet.readString();
            }).toList();
        }
    }
}
