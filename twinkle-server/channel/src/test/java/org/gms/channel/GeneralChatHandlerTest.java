package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.service.agent.PlayerSupportAgent;
import org.gms.i18n.I18n;
import org.gms.i18n.ResourceBundleI18nService;
import org.gms.service.agent.UnavailablePlayerSupportAgent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/** 玩家聊天接入值班 GM 的协议、异步回包和限流测试。 */
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

    @Test
    void agentQuestionIsAcceptedAndReturnedOnlyToCurrentSession() {
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        FakeSession session = sessionWithCharacter(1001L, 7L, "Hero");
        sessions.claim(7L, session);
        RecordingAgent agent = new RecordingAgent();
        GeneralChatHandler handler = new GeneralChatHandler(sessions, agent, 15);

        handler.handle(session, input("@gm 我的背包里还有药水吗", 0));

        assertThat(agent.questions).singleElement().satisfies(question -> {
            assertThat(question.characterId()).isEqualTo(7L);
            assertThat(question.characterName()).isEqualTo("Hero");
            assertThat(question.sessionId()).isEqualTo(1001L);
            assertThat(question.message()).isEqualTo("我的背包里还有药水吗");
        });
        assertThat(session.notices()).containsExactly(
                "AI 值班 GM 已受理，只会读取证，不会修改角色数据。",
                "[AI值班GM] 已落库背包中有 5 个药水。",
                "取证审计号：audit_agent_test");
    }

    @Test
    void repeatedQuestionWithinCooldownDoesNotCallAgentAgain() {
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        FakeSession session = sessionWithCharacter(1002L, 8L, "Mage");
        sessions.claim(8L, session);
        RecordingAgent agent = new RecordingAgent();
        GeneralChatHandler handler = new GeneralChatHandler(sessions, agent, 15);

        handler.handle(session, input("@gm 第一个问题", 0));
        handler.handle(session, input("@gm 第二个问题", 0));

        assertThat(agent.questions).hasSize(1);
        assertThat(session.notices().getLast()).contains("请求过于频繁");
    }

    @Test
    void unavailableAgentReturnsStableNotice() {
        PlayerSessionRegistry sessions = new PlayerSessionRegistry();
        FakeSession session = sessionWithCharacter(1003L, 9L, "Archer");
        sessions.claim(9L, session);
        GeneralChatHandler handler = new GeneralChatHandler(
                sessions, new UnavailablePlayerSupportAgent(), 15);

        handler.handle(session, input("@gm 帮帮我", 0));

        assertThat(session.notices()).containsExactly("AI 值班 GM 当前未启用。");
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

    private static final class RecordingAgent implements PlayerSupportAgent {
        private final List<PlayerQuestion> questions = new ArrayList<>();

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public CompletionStage<Reply> ask(PlayerQuestion question) {
            questions.add(question);
            return CompletableFuture.completedFuture(
                    new Reply("已落库背包中有 5 个药水。", List.of("audit_agent_test")));
        }
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
