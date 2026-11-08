package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.Portal;
import org.gms.domain.game.map.PortalType;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.logic.game.DefaultItemSystem;
import org.gms.logic.game.DefaultProgressionSystem;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 死亡复活：安全护符 30% 恢复、复活轮原地复活与回城兜底。 */
public class ReviveGameplayTest {
    private static final int SAFETY_CHARM = 5_130_000;
    private static final int WHEEL_OF_FORTUNE = 5_510_000;

    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();
    private final DefaultItemSystem items = new DefaultItemSystem(
            new DefaultVersionGate(), GameDataProvider.fixed(Map.of(), Map.of()));

    @Test public void safetyCharmReviveRestoresThirtyPercentHealthAndMana() {
        var origin = map(100);
        origin.setReturnMapId(300);
        var target = map(300);
        target.putPortal(portal(0, "sp", PortalType.MOVE, 10, 20));
        var player = joined(1, origin);
        player.character.setJob(100);
        player.character.setLevel(10);
        player.character.setExp(500);
        player.character.setMaxHp(200);
        player.character.setMaxMp(100);
        assertThat(items.giveItem(player.character, SAFETY_CHARM, 1)).isTrue();

        death().onDeath(player, player.character);
        assertThat(player.character.getExp()).isEqualTo(500);
        assertThat((Object) player.getAttr("safetyCharmUsed")).isEqualTo(true);

        player.character.setHp(0);
        player.character.setMp(10);
        transitions(Map.of(300, target)).revive(player, false);

        assertThat(player.character.getHp()).isEqualTo(60);
        assertThat(player.character.getMp()).isEqualTo(40);
        assertThat(player.character.getMap()).isEqualTo(300);
        assertThat((Object) player.getAttr("safetyCharmUsed")).isNull();
    }

    @Test public void wheelReviveKeepsMapRestoresFiftyHpAndConsumesItem() {
        var origin = map(100);
        origin.setReturnMapId(300);
        origin.putPortal(portal(0, "far", PortalType.MAP_PORTAL, 900, 900));
        origin.putPortal(portal(1, "spawn", PortalType.MAP_PORTAL, 50, 60));
        var player = joined(1, origin);
        player.character.setJob(100);
        player.character.setLevel(10);
        player.character.setExp(500);
        player.character.setMaxHp(200);
        player.character.setHp(0);
        player.character.setX(40);
        player.character.setY(55);
        assertThat(items.giveItem(player.character, WHEEL_OF_FORTUNE, 2)).isTrue();

        var change = new ByteArrayOutPacket();
        change.writeByte(1).writeInt(100).writeString("sp").writeByte(0).writeByte(1);
        new ChangeMapHandler(transitions(Map.of(100, origin)), false)
                .handle(player, new ByteArrayInPacket(change.getBytes()));

        assertThat(player.character.getMap()).isEqualTo(100);
        assertThat(player.character.getHp()).isEqualTo(50);
        assertThat(player.character.getExp()).isEqualTo(500);
        assertThat(player.character.getItemCount(WHEEL_OF_FORTUNE)).isEqualTo(1);
        assertThat(player.character.getSpawnPoint()).isEqualTo(1);
        assertThat(player.character.getX()).isEqualTo(50);
        assertThat(player.character.getY()).isEqualTo(60);
        assertThat(origin.characters()).containsExactly(player.character);
        assertThat(player.sent).anyMatch(packet -> opcode(packet) == SendOpcode.SET_FIELD.getValue());
        assertThat(player.sent).anyMatch(packet -> opcode(packet) == SendOpcode.INVENTORY_OPERATION.getValue());
        var wheels = new ByteArrayInPacket(wheelsLeft(player).getBytes());
        assertThat(wheels.readUnsignedShort()).isEqualTo(SendOpcode.SHOW_ITEM_GAIN_INCHAT.getValue());
        assertThat(wheels.readByte()).isEqualTo((byte) 0x15);
        assertThat(wheels.readByte()).isEqualTo((byte) 1);
        assertThat(wheels.available()).isZero();
    }

    @Test public void wheelRequestWithoutItemFallsBackToReturnMap() {
        var origin = map(100);
        origin.setReturnMapId(300);
        var target = map(300);
        target.putPortal(portal(0, "sp", PortalType.MOVE, 10, 20));
        var player = joined(1, origin);
        player.character.setHp(0);

        transitions(Map.of(300, target)).revive(player, true);

        assertThat(player.character.getMap()).isEqualTo(300);
        assertThat(player.character.getHp()).isEqualTo(50);
        assertThat(player.sent)
                .noneMatch(packet -> opcode(packet) == SendOpcode.SHOW_ITEM_GAIN_INCHAT.getValue());
    }

    @Test public void wheelItemIsIgnoredWithoutTheClientFlag() {
        var origin = map(100);
        origin.setReturnMapId(300);
        var target = map(300);
        target.putPortal(portal(0, "sp", PortalType.MOVE, 10, 20));
        var player = joined(1, origin);
        player.character.setHp(0);
        assertThat(items.giveItem(player.character, WHEEL_OF_FORTUNE, 1)).isTrue();

        transitions(Map.of(300, target)).revive(player, false);

        assertThat(player.character.getMap()).isEqualTo(300);
        assertThat(player.character.getItemCount(WHEEL_OF_FORTUNE)).isEqualTo(1);
        assertThat(player.sent)
                .noneMatch(packet -> opcode(packet) == SendOpcode.SHOW_ITEM_GAIN_INCHAT.getValue());
    }

    @Test public void safetyCharmIsNotConsumedInDojoMaps() {
        var dojo = map(925_020_000);
        var player = joined(1, dojo);
        player.character.setJob(100);
        player.character.setLevel(10);
        player.character.setExp(500);
        assertThat(items.giveItem(player.character, SAFETY_CHARM, 1)).isTrue();

        death().onDeath(player, player.character);

        int loss = V83ExpTable.requiredForLevel(10) / 10;
        assertThat(loss).isPositive();
        assertThat(player.character.getExp()).isEqualTo(500 - loss);
        assertThat(player.character.getItemCount(SAFETY_CHARM)).isEqualTo(1);
        assertThat((Object) player.getAttr("safetyCharmUsed")).isNull();
    }

    private GameplayTestSession joined(long id, MapleMap map) {
        var session = new GameplayTestSession(id, map);
        sessions.claim(id, session);
        return session;
    }

    private MapTransitionService transitions(Map<Integer, MapleMap> maps) {
        return new MapTransitionService(maps::get, null, null, 1, sessions);
    }

    private PlayerDeathService death() {
        return new PlayerDeathService(new DefaultProgressionSystem(new DefaultVersionGate()),
                sessions, Clock.systemUTC());
    }

    private static MapleMap map(int id) {
        var map = new MapleMap();
        map.setMapId(id);
        return map;
    }

    private static Portal portal(int id, String name, PortalType type, int x, int y) {
        var portal = new Portal(id, name, type, x, y);
        portal.setTargetMapId(999_999_999);
        return portal;
    }

    private static OutPacket wheelsLeft(GameplayTestSession session) {
        return session.sent.stream()
                .filter(packet -> opcode(packet) == SendOpcode.SHOW_ITEM_GAIN_INCHAT.getValue())
                .findFirst().orElseThrow();
    }

    private static int opcode(OutPacket packet) {
        return new ByteArrayInPacket(packet.getBytes()).readUnsignedShort();
    }
}
