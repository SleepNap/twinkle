package org.gms.channel;

import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.Portal;
import org.gms.domain.game.map.PortalType;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.replaceable.ItemSystem;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** 换图和拾取按真实地图/角色状态验证，覆盖并发认领和失败回滚。 */
public class MapAndDropGameplayTest {
    @Test public void identicalMapIdsKeepIndependentDropInventories() {
        var first = new GameplayTestSession(1, map(100));
        var second = new GameplayTestSession(2, map(100));
        var sessions = new PlayerSessionRegistry(); sessions.claim(1, first); sessions.claim(2, second);
        var data = GameDataProvider.fixed(Map.of(2000000, new ItemData(2000000)), Map.of());
        var items = new ItemSystem(new DefaultVersionGate(), data);
        items.giveItem(first.character, 2000000, 2); items.giveItem(second.character, 2000000, 7);
        try (var drops = new GroundDropService(items, data, sessions, Clock.systemUTC())) {
            assertThat(drops.drop(first.character, (byte) 2, (short) 1, 2)).isTrue();
            assertThat(drops.drop(second.character, (byte) 2, (short) 1, 7)).isTrue();
            var a = new ByteArrayInPacket(first.sent.getFirst().getBytes()); a.skip(3);
            var b = new ByteArrayInPacket(second.sent.getFirst().getBytes()); b.skip(3);
            int oid = a.readInt(); assertThat(b.readInt()).isEqualTo(oid);
            assertThat(drops.pickup(first.character, oid)).isTrue();
            assertThat(drops.pickup(second.character, oid)).isTrue();
            assertThat(first.character.getItemCount(2000000)).isEqualTo(2);
            assertThat(second.character.getItemCount(2000000)).isEqualTo(7);
        }
    }

    @Test public void portalUsesServerDestinationAndRejectsRemoteOrForgedRequests() {
        MapleMap origin = map(100), target = map(200);
        Portal exit = portal(1, "out", 200, "sp", 10, 20);
        origin.putPortal(exit);
        target.putPortal(portal(0, "sp", 100, "out", 50, 60));
        var session = new GameplayTestSession(1, origin);
        var transitions = new MapTransitionService(Map.of(100, origin, 200, target)::get, null, null, 1);
        assertThat(transitions.usePortal(session, "out", 300)).isFalse();
        session.character.setX(999);
        assertThat(transitions.usePortal(session, "out", -1)).isFalse();
        session.character.setX(10);
        assertThat(transitions.usePortal(session, "out", -1)).isTrue();
        assertThat(origin.characters()).isEmpty();
        assertThat(target.characters()).containsExactly(session.character);
        assertThat(session.character.getX()).isEqualTo(50);
        assertThat(session.character.getMap()).isEqualTo(200);
        assertThat(transitions.warp(session, 100)).isFalse();
        new PlayerMapTransitionHandler().handle(session, new ByteArrayInPacket(new byte[0]));
        assertThat((Object) session.getAttr("mapTransition")).isNull();
        ByteArrayInPacket wire = new ByteArrayInPacket(session.sent.getFirst().getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.SET_FIELD.getValue());
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readInt()).isZero();
        assertThat(wire.readByte()).isZero();
        assertThat(wire.readInt()).isEqualTo(200);
    }

    @Test public void pickupIsAwardedOnceUnderConcurrentRequests() throws Exception {
        MapleMap map = map(100);
        var owner = new GameplayTestSession(1, map);
        var first = new GameplayTestSession(2, map);
        var second = new GameplayTestSession(3, map);
        var sessions = new PlayerSessionRegistry();
        sessions.claim(1, owner); sessions.claim(2, first); sessions.claim(3, second);
        ItemData potion = new ItemData(2000000);
        var data = GameDataProvider.fixed(Map.of(2000000, potion), Map.of());
        var items = new ItemSystem(new DefaultVersionGate(), data);
        items.giveItem(owner.character, 2000000, 3);
        owner.character.getInventory(InventoryType.USE).getItem((short) 1).setOwner("来源");
        try (var drops = new GroundDropService(items, data, sessions, Clock.systemUTC());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThat(drops.drop(owner.character, (byte) 2, (short) 1, 3)).isTrue();
            var spawned = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
            spawned.skip(3);
            int oid = spawned.readInt();
            CountDownLatch start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return drops.pickup(first.character, oid); });
            var b = executor.submit(() -> { start.await(); return drops.pickup(second.character, oid); });
            start.countDown();
            assertThat(a.get() ^ b.get()).isTrue();
            assertThat(first.character.getItemCount(2000000) + second.character.getItemCount(2000000)).isEqualTo(3);
            assertThat(drops.pickup(owner.character, oid)).isFalse();
            var winner = first.character.getItemCount(2000000) > 0 ? first.character : second.character;
            assertThat(winner.getInventory(InventoryType.USE).getItem((short) 1).getOwner()).isEqualTo("来源");
        }
    }

    @Test public void fullBagAndWrongMapCannotConsumeDrop() {
        MapleMap map = map(100);
        var owner = new GameplayTestSession(1, map);
        var picker = new GameplayTestSession(2, map);
        var sessions = new PlayerSessionRegistry();
        sessions.claim(1, owner); sessions.claim(2, picker);
        var data = GameDataProvider.fixed(Map.of(2000000, new ItemData(2000000)), Map.of());
        var items = new ItemSystem(new DefaultVersionGate(), data);
        items.giveItem(owner.character, 2000000, 1);
        items.giveItem(picker.character, 2000000, 2400);
        try (var drops = new GroundDropService(items, data, sessions,
                Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC))) {
            assertThat(drops.drop(owner.character, (byte) 2, (short) 1, 1)).isTrue();
            var spawned = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
            spawned.skip(3);
            int oid = spawned.readInt();
            assertThat(drops.pickup(picker.character, oid)).isFalse();
            items.takeItem(picker.character, 2000000, 1);
            picker.character.setMapObject(map(100));
            assertThat(drops.pickup(picker.character, oid)).isFalse();
            picker.character.setMapObject(map);
            assertThat(drops.pickup(picker.character, oid)).isTrue();
        }
    }

    private static MapleMap map(int id) {
        MapleMap map = new MapleMap(); map.setMapId(id); return map;
    }
    private static Portal portal(int id, String name, int target, String targetName, int x, int y) {
        Portal portal = new Portal(id, name, PortalType.MOVE, x, y);
        portal.setId(id); portal.setName(name); portal.setTargetMapId(target);
        portal.setTargetPortalName(targetName); portal.setX(x); portal.setY(y);
        return portal;
    }
}
