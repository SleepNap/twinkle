package org.gms.channel;

import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.Portal;
import org.gms.domain.game.map.PortalType;
import org.gms.domain.game.skill.ActiveBuff;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

public class PlayerVisibilityTest {
    @Test public void spawnEncodesRemoteStateLookAndSignedPositionAtExactOffsets() {
        var session = new GameplayTestSession(17, map(100)); var chr = session.character;
        chr.setLevel(32); chr.setJob(200); chr.setX(-321); chr.setY(87); chr.setStance(5); chr.setFoothold(19);
        chr.setChairItemId(3010000);
        var weapon = new Equip(1372000); weapon.setPosition((short) -11);
        chr.getInventory(InventoryType.EQUIP).putAtSlot((short) -11, weapon);
        var in = new ByteArrayInPacket(PlayerPresencePackets.spawn(chr).getBytes());
        assertThat(in.readUnsignedShort()).isEqualTo(SendOpcode.SPAWN_PLAYER.getValue());
        assertThat(in.readInt()).isEqualTo(17); assertThat(in.readByte()).isEqualTo((byte) 32);
        assertThat(in.readString()).isEqualTo("Player17"); assertThat(in.readString()).isEmpty();
        assertThat(in.readBytes(6)).containsOnly((byte) 0);
        byte[] state = in.readBytes(128); assertThat(state[6]).isEqualTo((byte) 0xFC); assertThat(state[7]).isOne();
        state[6] = 0; state[7] = 0; assertThat(state).containsOnly((byte) 0);
        assertThat(in.readShort()).isEqualTo((short) 200); in.skip(11);
        assertThat(in.readByte()).isEqualTo((byte) 11); assertThat(in.readInt()).isEqualTo(1372000);
        assertThat(in.readByte()).isEqualTo((byte) -1); assertThat(in.readByte()).isEqualTo((byte) -1);
        assertThat(in.readBytes(16)).containsOnly((byte) 0);
        in.skip(8); assertThat(in.readInt()).isEqualTo(3010000);
        assertThat(in.readShort()).isEqualTo((short) -321); assertThat(in.readShort()).isEqualTo((short) 87);
        assertThat(in.readByte()).isEqualTo((byte) 5); assertThat(in.readShort()).isEqualTo((short) 19);
        assertThat(in.readShort()).isZero(); assertThat(in.readInt()).isOne(); assertThat(in.readLong()).isZero();
        assertThat(in.readBytes(9)).containsOnly((byte) 0); assertThat(in.available()).isZero();
    }

    @Test public void entryIsBidirectionalAfterReadyIdempotentAndIsolatedByMapInstance() {
        var sessions = new PlayerSessionRegistry(); var map = map(100);
        var first = joined(sessions, 1, map); var second = joined(sessions, 2, map);
        var otherChannelMap = joined(sessions, 3, map(100));
        sessions.visibility().enter(first); sessions.visibility().enter(otherChannelMap);
        second.setAttr("mapTransition", true); sessions.visibility().enter(second);
        assertThat(first.sent).isEmpty(); assertThat(second.sent).isEmpty();
        second.setAttr("mapTransition", null); second.setAttr("mapVisibilityReady", false);
        sessions.broadcastToMap(map, PlayerPresencePackets.expression(1, 3), 1);
        assertThat(second.sent).isEmpty(); // 确认与生成之间也不能提前接收动作包
        sessions.visibility().enter(second); sessions.visibility().enter(second);
        assertThat((Object) second.getAttr("mapVisibilityReady")).isEqualTo(true);
        assertThat(opcodes(first)).containsExactly(SendOpcode.SPAWN_PLAYER.getValue());
        assertThat(opcodes(second)).containsExactly(SendOpcode.SPAWN_PLAYER.getValue());
        assertThat(otherChannelMap.sent).isEmpty();
        assertThat(id(first.sent.getFirst())).isEqualTo(2); assertThat(id(second.sent.getFirst())).isEqualTo(1);
    }

    @Test public void warpRemovesOldAvatarAndWaitsForLoadBeforeSpawningAtDestination() {
        var sessions = new PlayerSessionRegistry(); var origin = map(100); var target = map(200);
        var player = joined(sessions, 1, origin); var oldPeer = joined(sessions, 2, origin);
        var newPeer = joined(sessions, 3, target);
        sessions.visibility().enter(player); sessions.visibility().enter(oldPeer); sessions.visibility().enter(newPeer);
        player.sent.clear(); oldPeer.sent.clear(); newPeer.sent.clear(); player.character.setChairItemId(3010000);
        var transitions = new MapTransitionService(id -> target, null, null, 1, sessions);
        assertThat(transitions.warp(player, 200)).isTrue();
        assertThat(opcodes(oldPeer)).containsExactly(SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue());
        assertThat(newPeer.sent).isEmpty(); assertThat(player.character.getChairItemId()).isZero();
        assertThat(opcodes(player).getFirst()).isEqualTo(SendOpcode.SET_FIELD.getValue());
        new PlayerMapTransitionHandler().handle(player, new ByteArrayInPacket(new byte[0]));
        sessions.visibility().enter(player);
        assertThat(opcodes(newPeer)).containsExactly(SendOpcode.SPAWN_PLAYER.getValue());
        assertThat(id(newPeer.sent.getFirst())).isEqualTo(1);
        assertThat(opcodes(oldPeer)).hasSize(1);
    }

    @Test public void replacementAndLateDisconnectDoNotRemoveNewGeneration() {
        var sessions = new PlayerSessionRegistry(); var origin = map(100); var target = map(200);
        var old = joined(sessions, 1, origin); var oldPeer = joined(sessions, 2, origin);
        var newPeer = joined(sessions, 3, target);
        sessions.visibility().enter(old); sessions.visibility().enter(oldPeer); sessions.visibility().enter(newPeer);
        oldPeer.sent.clear(); newPeer.sent.clear();
        var replacement = joined(sessions, 1, target); sessions.visibility().enter(replacement);
        assertThat(origin.characters()).doesNotContain(old.character);
        assertThat(opcodes(oldPeer)).containsExactly(SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue());
        assertThat(opcodes(newPeer)).containsExactly(SendOpcode.SPAWN_PLAYER.getValue());
        assertThat(sessions.unregister(1, old)).isFalse(); sessions.visibility().enter(old);
        assertThat(opcodes(newPeer)).hasSize(1);
        assertThat(sessions.unregister(1, replacement)).isTrue();
        assertThat(opcodes(newPeer)).containsExactly(SendOpcode.SPAWN_PLAYER.getValue(), SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue());
    }

    @Test public void simultaneousEntryProducesOneSpawnPerPeer() throws Exception {
        var sessions = new PlayerSessionRegistry(); var map = map(100);
        var first = joined(sessions, 1, map); var second = joined(sessions, 2, map);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); sessions.visibility().enter(first); return true; });
            var b = executor.submit(() -> { start.await(); sessions.visibility().enter(second); return true; });
            start.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
        }
        assertThat(opcodes(first)).containsExactly(SendOpcode.SPAWN_PLAYER.getValue());
        assertThat(opcodes(second)).containsExactly(SendOpcode.SPAWN_PLAYER.getValue());
    }

    @Test public void lateJoinReceivesOnlyLiveBuffsAfterSpawn() {
        var sessions = new PlayerSessionRegistry(); var map = map(100);
        var buffed = joined(sessions, 1, map); var arriving = joined(sessions, 2, map);
        buffed.character.putBuff(new ActiveBuff(1L << 33, 7, 1001003, System.currentTimeMillis() + 60000));
        buffed.character.putBuff(new ActiveBuff(1L << 36, 8, 3001003, 1));
        sessions.visibility().enter(buffed); sessions.visibility().enter(arriving);
        assertThat(opcodes(arriving)).containsExactly(SendOpcode.SPAWN_PLAYER.getValue(), SendOpcode.GIVE_FOREIGN_BUFF.getValue());
        var in = new ByteArrayInPacket(arriving.sent.get(1).getBytes()); in.skip(6);
        assertThat(in.readLong()).isZero(); assertThat(in.readLong()).isEqualTo(1L << 33);
        assertThat(in.readShort()).isEqualTo((short) 7); in.skip(6); assertThat(in.available()).isZero();
    }

    private static GameplayTestSession joined(PlayerSessionRegistry sessions, long id, MapleMap map) {
        var session = new GameplayTestSession(id, map); sessions.claim(id, session); return session;
    }
    private static MapleMap map(int id) {
        var map = new MapleMap(); map.setMapId(id);
        map.putPortal(new Portal(0, "sp", PortalType.MAP_PORTAL, 30, 40)); return map;
    }
    private static List<Integer> opcodes(GameplayTestSession session) {
        return session.sent.stream().map(packet -> new ByteArrayInPacket(packet.getBytes()).readUnsignedShort()).toList();
    }
    private static int id(OutPacket packet) {
        var in = new ByteArrayInPacket(packet.getBytes()); in.skip(2); return in.readInt();
    }
}
