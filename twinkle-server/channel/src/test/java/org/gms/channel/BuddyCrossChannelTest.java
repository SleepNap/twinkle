package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.event.InProcessEventBus;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.persistence.entity.BuddyListEntity;
import org.gms.service.intercoord.IntercoordService;
import org.gms.service.intercoord.PlayerPresenceService.PlayerActivity;
import org.gms.service.intercoord.PlayerPresenceService.PlayerPresence;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨频道好友：在线频道显示、登录/断线刷新与异频道申请投递。 */
public class BuddyCrossChannelTest {
    private final BuddyFixtures.Repository repository = new BuddyFixtures.Repository();
    private final BuddyFixtures.Characters characters = new BuddyFixtures.Characters();
    private final InProcessEventBus bus = new InProcessEventBus();
    private final Map<Long, Integer> presence = new ConcurrentHashMap<>();
    private final IntercoordService intercoord = presenceService();
    private final PlayerSessionRegistry channel1 = new PlayerSessionRegistry();
    private final PlayerSessionRegistry channel2 = new PlayerSessionRegistry();
    private final BuddyHandler handler1 = new BuddyHandler(1, intercoord, bus, channel1, repository, characters);
    private final BuddyHandler handler2 = new BuddyHandler(2, intercoord, bus, channel2, repository, characters);

    @Test public void loginShowsBuddyOnlineOnOtherChannelAndRefreshesThem() {
        var one = joined(channel1, 1);
        var two = joined(channel2, 2);
        online(1, 1);
        online(2, 2);
        accepted(1, 2);

        handler1.initialize(one);

        assertThat(buddyIds(one.sent.getLast())).containsExactly(2L);
        assertThat(entryChannel(one.sent.getLast(), 2)).isEqualTo(1);
        assertThat(buddyIds(two.sent.getLast())).containsExactly(1L);
        assertThat(entryChannel(two.sent.getLast(), 1)).isZero();
    }

    @Test public void offlineRefreshesBuddyOnOtherChannelAsOffline() {
        var one = joined(channel1, 1);
        var two = joined(channel2, 2);
        online(1, 1);
        online(2, 2);
        accepted(1, 2);
        handler1.initialize(one);
        assertThat(channel1.unregister(1, one)).isTrue();
        presence.remove(1L);
        two.sent.clear();

        handler1.offline(one);

        assertThat(buddyIds(two.sent.getLast())).containsExactly(1L);
        assertThat(entryChannel(two.sent.getLast(), 1)).isEqualTo(-2);
    }

    @Test public void addRequestReachesTargetOnOtherChannel() {
        var one = joined(channel1, 1);
        var two = joined(channel2, 2);
        online(1, 1);
        online(2, 2);
        characters.records.put(2L, BuddyFixtures.character(2, "Player2"));

        handler1.handle(one, addRequest("Player2", "Friends"));

        assertThat(repository.findByOwner(1)).hasSize(1);
        assertThat(repository.findByOwner(1).getFirst().getStatus()).isEqualTo(BuddyListEntity.PENDING);
        assertThat(repository.findByOwner(2)).hasSize(1);

        var packet = new ByteArrayInPacket(two.sent.getLast().getBytes());
        assertThat(packet.readUnsignedShort()).isEqualTo(SendOpcode.BUDDYLIST.getValue());
        assertThat(packet.readByte()).isEqualTo((byte) 9);
        assertThat(packet.readInt()).isOne();
        assertThat(packet.readString()).isEqualTo("Player1");
        assertThat(packet.readInt()).isOne();
        assertThat(readFixed(packet, 11)).isEqualTo("Player1");
        packet.skip(3);
        assertThat(packet.readInt()).isEqualTo(0x0F);
        assertThat(readFixed(packet, 13)).isEqualTo("Friends");
        packet.readByte();
        assertThat(packet.readInt()).isEqualTo(2);
        assertThat(packet.available()).isZero();
    }

    @Test public void acceptRefreshesBuddyOnOtherChannel() {
        var one = joined(channel1, 1);
        var two = joined(channel2, 2);
        online(1, 1);
        online(2, 2);
        pending(1, 2);
        pending(2, 1);

        handler1.handle(one, idRequest(2, 2));

        assertThat(repository.findByOwner(1).getFirst().getStatus()).isEqualTo(BuddyListEntity.ACCEPTED);
        assertThat(repository.findByOwner(2).getFirst().getStatus()).isEqualTo(BuddyListEntity.ACCEPTED);
        assertThat(buddyIds(one.sent.getLast())).containsExactly(2L);
        assertThat(entryChannel(one.sent.getLast(), 2)).isEqualTo(1);
        assertThat(buddyIds(two.sent.getLast())).containsExactly(1L);
        assertThat(entryChannel(two.sent.getLast(), 1)).isZero();
    }

    @Test public void deleteRefreshesBuddyOnOtherChannel() {
        var one = joined(channel1, 1);
        var two = joined(channel2, 2);
        online(1, 1);
        online(2, 2);
        accepted(1, 2);

        handler1.handle(one, idRequest(3, 2));

        assertThat(repository.findByOwner(2)).isEmpty();
        assertThat(buddyIds(two.sent.getLast())).isEmpty();
    }

    @Test public void presenceFailureFallsBackToOfflineInsteadOfDroppingList() {
        IntercoordService broken = (IntercoordService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{IntercoordService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("presence")) {
                        throw new IllegalStateException("coordinator unavailable");
                    }
                    return defaultAnswer(proxy, method, args);
                });
        var handler = new BuddyHandler(1, broken, bus, channel1, repository, characters);
        var one = joined(channel1, 1);
        accepted(1, 2);
        characters.records.put(2L, BuddyFixtures.character(2, "Player2"));

        handler.initialize(one);

        assertThat(buddyIds(one.sent.getLast())).containsExactly(2L);
        assertThat(entryChannel(one.sent.getLast(), 2)).isEqualTo(-2);
    }

    @Test public void sameChannelBuddyIsRefreshedOnlyOnce() {
        var one = joined(channel1, 1);
        var two = joined(channel1, 2);
        online(1, 1);
        online(2, 1);
        accepted(1, 2);

        handler1.initialize(one);

        assertThat(two.sent).hasSize(1);
    }

    @Test public void closedHandlerStopsReceivingCrossChannelNotices() {
        var one = joined(channel1, 1);
        var two = joined(channel2, 2);
        online(1, 1);
        online(2, 2);
        accepted(1, 2);
        handler2.close();

        handler1.initialize(one);

        assertThat(two.sent).isEmpty();
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

    private GameplayTestSession joined(PlayerSessionRegistry sessions, long id) {
        var session = new GameplayTestSession(id, new MapleMap());
        sessions.claim(id, session);
        // 异频道好友的名字只能从角色表补全，真实环境里角色档始终存在。
        characters.records.put(id, BuddyFixtures.character(id, "Player" + id));
        return session;
    }

    private void online(long characterId, int channel) {
        presence.put(characterId, channel);
    }

    private void pending(long owner, long buddy) {
        repository.insertIfAbsent(BuddyFixtures.row(owner, buddy, BuddyListEntity.PENDING));
    }

    private void accepted(long owner, long buddy) {
        pending(owner, buddy);
        pending(buddy, owner);
        repository.updateStatus(owner, buddy, BuddyListEntity.ACCEPTED);
        repository.updateStatus(buddy, owner, BuddyListEntity.ACCEPTED);
    }

    private static List<Long> buddyIds(OutPacket packet) {
        var wire = new ByteArrayInPacket(packet.getBytes());
        wire.readUnsignedShort();
        assertThat(wire.readByte()).isEqualTo((byte) 7);
        int count = wire.readByte();
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add((long) wire.readInt());
            wire.skip(13);
            wire.readByte();
            wire.readInt();
            wire.skip(13);
            wire.readInt();
        }
        return ids;
    }

    private static int entryChannel(OutPacket packet, long characterId) {
        var wire = new ByteArrayInPacket(packet.getBytes());
        wire.readUnsignedShort();
        wire.readByte();
        int count = wire.readByte();
        for (int i = 0; i < count; i++) {
            int id = wire.readInt();
            wire.skip(13);
            wire.readByte();
            int channel = wire.readInt();
            wire.skip(13);
            wire.readInt();
            if (id == characterId) return channel;
        }
        throw new AssertionError("好友列表中未找到 " + characterId);
    }

    private static ByteArrayInPacket addRequest(String name, String group) {
        var packet = new ByteArrayOutPacket();
        packet.writeByte(1).writeString(name).writeString(group);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private static ByteArrayInPacket idRequest(int mode, int id) {
        var packet = new ByteArrayOutPacket();
        packet.writeByte(mode).writeInt(id);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private static String readFixed(InPacket packet, int length) {
        return new String(packet.readBytes(length), InPacket.DEFAULT_CHARSET).trim();
    }
}
