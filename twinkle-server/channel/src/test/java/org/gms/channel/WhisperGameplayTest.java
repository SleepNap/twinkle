package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.event.InProcessEventBus;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.service.intercoord.IntercoordService;
import org.gms.service.intercoord.PlayerPresenceService.PlayerActivity;
import org.gms.service.intercoord.PlayerPresenceService.PlayerPresence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 悄悄话 v83 收发包、跨频道投递与位置查询回归。 */
public class WhisperGameplayTest {
    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();
    private final InProcessEventBus bus = new InProcessEventBus();
    private final Map<Long, Integer> presence = new ConcurrentHashMap<>();
    private final IntercoordService intercoord = presenceService();
    private final BuddyFixtures.Characters characters = new BuddyFixtures.Characters();
    private final WhisperHandler handler = new WhisperHandler(1, intercoord, bus, sessions, characters);

    @Test public void sameChannelWhisperDeliversReceivePacketAndResult() {
        var sender = joined(sessions, 1);
        var target = joined(sessions, 2);

        handler.handle(sender, request(0x06, "Player2", "hello"));

        var receive = new ByteArrayInPacket(target.sent.getLast().getBytes());
        assertThat(receive.readUnsignedShort()).isEqualTo(SendOpcode.WHISPER.getValue());
        assertThat(receive.readByte()).isEqualTo((byte) 0x12);
        assertThat(receive.readString()).isEqualTo("Player1");
        assertThat(receive.readByte()).isZero();
        assertThat(receive.readByte()).isZero();
        assertThat(receive.readString()).isEqualTo("hello");
        assertThat(receive.available()).isZero();

        var result = new ByteArrayInPacket(sender.sent.getLast().getBytes());
        assertThat(result.readUnsignedShort()).isEqualTo(SendOpcode.WHISPER.getValue());
        assertThat(result.readByte()).isEqualTo((byte) 0x0A);
        assertThat(result.readString()).isEqualTo("Player2");
        assertThat(result.readByte()).isEqualTo((byte) 1);
        assertThat(result.available()).isZero();
    }

    @Test public void offlineTargetGetsFailureResult() {
        var sender = joined(sessions, 1);

        handler.handle(sender, request(0x06, "Ghost", "hello"));

        var result = new ByteArrayInPacket(sender.sent.getLast().getBytes());
        assertThat(result.readUnsignedShort()).isEqualTo(SendOpcode.WHISPER.getValue());
        assertThat(result.readByte()).isEqualTo((byte) 0x0A);
        assertThat(result.readString()).isEqualTo("Ghost");
        assertThat(result.readByte()).isZero();
        assertThat(result.available()).isZero();
    }

    @Test public void crossChannelWhisperIsDeliveredByTargetChannel() {
        var sender = joined(sessions, 1);
        var otherChannel = new PlayerSessionRegistry();
        var target = joined(otherChannel, 2);
        presence.put(2L, 2);
        try (var subscriber = new ChannelMessageSubscriber(2, intercoord, otherChannel, bus)) {
            handler.handle(sender, request(0x06, "Player2", "cross"));

            var receive = new ByteArrayInPacket(target.sent.getLast().getBytes());
            assertThat(receive.readUnsignedShort()).isEqualTo(SendOpcode.WHISPER.getValue());
            assertThat(receive.readByte()).isEqualTo((byte) 0x12);
            assertThat(receive.readString()).isEqualTo("Player1");
            assertThat(receive.readByte()).isZero();
            assertThat(receive.readByte()).isZero();
            assertThat(receive.readString()).isEqualTo("cross");
            assertThat(receive.available()).isZero();

            var result = new ByteArrayInPacket(sender.sent.getLast().getBytes());
            assertThat(result.readUnsignedShort()).isEqualTo(SendOpcode.WHISPER.getValue());
            assertThat(result.readByte()).isEqualTo((byte) 0x0A);
            assertThat(result.readString()).isEqualTo("Player2");
            assertThat(result.readByte()).isEqualTo((byte) 1);
            assertThat(subscriber).isNotNull();
        }
    }

    @Test public void findSameChannelReturnsMapAndPosition() {
        var sender = joined(sessions, 1);
        var target = joined(sessions, 2);
        target.character.setMap(100000000);
        target.character.setX(30);
        target.character.setY(-40);

        handler.handle(sender, request(0x05, "Player2", null));

        var result = new ByteArrayInPacket(sender.sent.getLast().getBytes());
        assertThat(result.readUnsignedShort()).isEqualTo(SendOpcode.WHISPER.getValue());
        assertThat(result.readByte()).isEqualTo((byte) 0x09);
        assertThat(result.readString()).isEqualTo("Player2");
        assertThat(result.readByte()).isEqualTo((byte) 1);
        assertThat(result.readInt()).isEqualTo(100000000);
        assertThat(result.readInt()).isEqualTo(30);
        assertThat(result.readInt()).isEqualTo(-40);
        assertThat(result.available()).isZero();
    }

    @Test public void findOtherChannelReturnsTargetChannel() {
        var sender = joined(sessions, 1);
        var otherChannel = new PlayerSessionRegistry();
        joined(otherChannel, 2);
        presence.put(2L, 2);

        handler.handle(sender, request(0x05, "Player2", null));

        var result = new ByteArrayInPacket(sender.sent.getLast().getBytes());
        assertThat(result.readUnsignedShort()).isEqualTo(SendOpcode.WHISPER.getValue());
        assertThat(result.readByte()).isEqualTo((byte) 0x09);
        assertThat(result.readString()).isEqualTo("Player2");
        assertThat(result.readByte()).isEqualTo((byte) 3);
        assertThat(result.readInt()).isOne();
        assertThat(result.available()).isZero();
    }

    @Test public void findUnknownTargetReturnsFailure() {
        var sender = joined(sessions, 1);

        handler.handle(sender, request(0x05, "Ghost", null));

        var result = new ByteArrayInPacket(sender.sent.getLast().getBytes());
        assertThat(result.readUnsignedShort()).isEqualTo(SendOpcode.WHISPER.getValue());
        assertThat(result.readByte()).isEqualTo((byte) 0x0A);
        assertThat(result.readString()).isEqualTo("Ghost");
        assertThat(result.readByte()).isZero();
    }

    @Test public void tooLongMessageIsDroppedWithoutResponse() {
        var sender = joined(sessions, 1);
        joined(sessions, 2);

        handler.handle(sender, request(0x06, "Player2", "x".repeat(128)));

        assertThat(sender.sent).isEmpty();
    }

    @Test public void malformedPacketIsIgnored() {
        var sender = joined(sessions, 1);

        handler.handle(sender, new ByteArrayInPacket(new byte[]{0x06}));

        assertThat(sender.sent).isEmpty();
    }

    private GameplayTestSession joined(PlayerSessionRegistry registry, long id) {
        var session = new GameplayTestSession(id, new MapleMap());
        registry.claim(id, session);
        characters.records.put(id, BuddyFixtures.character(id, "Player" + id));
        return session;
    }

    private static ByteArrayInPacket request(int flags, String name, String content) {
        var packet = new ByteArrayOutPacket();
        packet.writeByte(flags).writeString(name);
        if (content != null) packet.writeString(content);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private IntercoordService presenceService() {
        return (IntercoordService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IntercoordService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("presence")) {
                        long playerId = (Long) args[0];
                        return Optional.ofNullable(presence.get(playerId)).map(channel ->
                                new PlayerPresence(playerId, 0, channel, PlayerActivity.IN_CHANNEL, null));
                    }
                    return defaultAnswer(proxy, method, args);
                });
    }

    private static Object defaultAnswer(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "locate" -> Optional.empty();
            case "channel" -> Optional.empty();
            case "channels" -> Map.of();
            case "toString" -> "PresenceStub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> {
                Class<?> type = method.getReturnType();
                if (type == boolean.class) yield false;
                if (type == int.class) yield 0;
                if (type == long.class) yield 0L;
                yield null;
            }
        };
    }
}
