package org.gms.channel;

import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.item.EquipmentData;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.logic.game.DefaultItemSystem;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 怪物掉落目录、归属窗口和拾取校验；不覆盖真实 WZ 掉落数据。 */
public class MonsterDropGameplayTest {

    @Test public void monsterDropsMesoAndItemOwnedByKiller(@TempDir Path root) throws Exception {
        var clock = new MutableClock(1_000);
        var catalog = new MonsterDropCatalog(Files.writeString(root.resolve("drops.properties"),
                "100100=meso:10:10,2000000:100:2:2\n").toString());
        var fixture = fixture(clock, catalog);
        fixture.service.onKill(fixture.killer.character, fixture.monster);

        var killerSpawns = spawns(fixture.killer);
        assertThat(killerSpawns).hasSize(2);
        assertThat(fixture.stranger.sent).hasSize(2);
        var meso = killerSpawns.stream().filter(Spawn::meso).findFirst().orElseThrow();
        var item = killerSpawns.stream().filter(spawn -> !spawn.meso()).findFirst().orElseThrow();
        assertThat(meso.value()).isEqualTo(10);
        assertThat(item.value()).isEqualTo(2000000);

        assertThat(fixture.drops.pickup(fixture.stranger.character, meso.oid())).isFalse();
        assertThat(fixture.drops.pickup(fixture.stranger.character, item.oid())).isFalse();
        assertThat(fixture.drops.pickup(fixture.killer.character, meso.oid())).isTrue();
        assertThat(fixture.killer.character.getMeso()).isEqualTo(10);
        assertThat(fixture.drops.pickup(fixture.killer.character, item.oid())).isTrue();
        assertThat(fixture.killer.character.getItemCount(2000000)).isEqualTo(2);
        assertThat(fixture.stranger.character.getMeso()).isZero();
        assertThat(fixture.stranger.character.getItemCount(2000000)).isZero();
    }

    @Test public void ownerWindowExpiryAllowsOtherPlayers(@TempDir Path root) throws Exception {
        var clock = new MutableClock(1_000);
        var catalog = new MonsterDropCatalog(Files.writeString(root.resolve("meso.properties"),
                "100100=meso:7:7\n").toString());
        var fixture = fixture(clock, catalog);
        fixture.service.onKill(fixture.killer.character, fixture.monster);
        var drop = spawns(fixture.killer).getFirst();

        assertThat(fixture.drops.pickup(fixture.stranger.character, drop.oid())).isFalse();
        clock.advance(MonsterDropService.OWNER_WINDOW_MILLIS + 1);
        assertThat(fixture.drops.pickup(fixture.stranger.character, drop.oid())).isTrue();
        assertThat(fixture.stranger.character.getMeso()).isEqualTo(7);
    }

    @Test public void unconfiguredMobAndInvalidItemCreateNoDrop(@TempDir Path root) throws Exception {
        var clock = new MutableClock(1_000);
        var catalog = new MonsterDropCatalog(Files.writeString(root.resolve("empty.properties"), "").toString());
        var fixture = fixture(clock, catalog);
        fixture.service.onKill(fixture.killer.character, fixture.monster);
        assertThat(fixture.killer.sent).isEmpty();
        assertThat(fixture.stranger.sent).isEmpty();
    }

    @Test public void partyMembersCanPickUpDuringOwnerWindow(@TempDir Path root) throws Exception {
        var clock = new MutableClock(1_000);
        var catalog = new MonsterDropCatalog(Files.writeString(root.resolve("party.properties"),
                "100100=meso:7:7\n").toString());
        var fixture = fixture(clock, catalog);
        fixture.killer.character.setParty(5);
        fixture.mate.character.setParty(5);
        fixture.stranger.character.setParty(6);
        fixture.service.onKill(fixture.killer.character, fixture.monster);
        var drop = spawns(fixture.killer).getFirst();

        assertThat(drop.owner()).isEqualTo(5);
        assertThat(fixture.drops.pickup(fixture.stranger.character, drop.oid())).isFalse();
        assertThat(fixture.drops.pickup(fixture.mate.character, drop.oid())).isTrue();
        assertThat(fixture.mate.character.getMeso()).isEqualTo(7);
    }

    @Test public void partyOwnerWindowExpiresToOtherPlayers(@TempDir Path root) throws Exception {
        var clock = new MutableClock(1_000);
        var catalog = new MonsterDropCatalog(Files.writeString(root.resolve("party-expiry.properties"),
                "100100=meso:9:9\n").toString());
        var fixture = fixture(clock, catalog);
        fixture.killer.character.setParty(5);
        fixture.mate.character.setParty(5);
        fixture.service.onKill(fixture.killer.character, fixture.monster);
        var drop = spawns(fixture.killer).getFirst();

        assertThat(fixture.drops.pickup(fixture.stranger.character, drop.oid())).isFalse();
        clock.advance(MonsterDropService.OWNER_WINDOW_MILLIS + 1);
        assertThat(fixture.drops.pickup(fixture.stranger.character, drop.oid())).isTrue();
        assertThat(fixture.stranger.character.getMeso()).isEqualTo(9);
    }

    @Test public void equipmentDropCreatesFullInstanceAndCanBePickedUp(@TempDir Path root) throws Exception {
        var clock = new MutableClock(1_000);
        var catalog = new MonsterDropCatalog(Files.writeString(root.resolve("equipment.properties"),
                "100100=1302000:100:1:1\n").toString());
        var fixture = fixture(clock, catalog);
        fixture.service.onKill(fixture.killer.character, fixture.monster);
        var drop = spawns(fixture.killer).getFirst();

        assertThat(drop.meso()).isFalse();
        assertThat(drop.value()).isEqualTo(1302000);
        assertThat(fixture.drops.pickup(fixture.stranger.character, drop.oid())).isFalse();
        assertThat(fixture.drops.pickup(fixture.killer.character, drop.oid())).isTrue();
        var equip = (Equip) fixture.killer.character.getInventory(InventoryType.EQUIP).getItem((short) 1);
        assertThat(equip).isNotNull();
        assertThat(equip.getWAtk()).isEqualTo((short) 30);
        assertThat(equip.getStrStat()).isEqualTo((short) 7);
        assertThat(equip.getUpgradeSlots()).isEqualTo((byte) 5);
    }

    @Test public void equipmentDropRejectsQuantityAboveOne(@TempDir Path root) throws Exception {
        var clock = new MutableClock(1_000);
        var catalog = new MonsterDropCatalog(Files.writeString(root.resolve("equipment-stack.properties"),
                "100100=1302000:100:2:2\n").toString());
        var fixture = fixture(clock, catalog);
        fixture.service.onKill(fixture.killer.character, fixture.monster);
        assertThat(fixture.killer.sent).isEmpty();
    }

    private static Fixture fixture(MutableClock clock, MonsterDropCatalog catalog) {
        var sessions = new PlayerSessionRegistry();
        var map = new MapleMap();
        map.setMapId(100);
        var killer = new GameplayTestSession(1, map);
        var mate = new GameplayTestSession(2, map);
        var stranger = new GameplayTestSession(3, map);
        sessions.claim(1, killer);
        sessions.claim(2, mate);
        sessions.claim(3, stranger);
        var mob = new MobData(100100);
        mob.setMaxHp(100);
        var monster = new MapleMonster(mob);
        monster.setObjectId(7);
        monster.setX(10);
        monster.setY(10);
        map.addMonster(monster);
        var data = GameDataProvider.fixed(Map.of(2000000, new ItemData(2000000),
                1302000, sword()), Map.of(100100, mob));
        var items = new DefaultItemSystem(new DefaultVersionGate(), data);
        var drops = new GroundDropService(items, data, sessions, clock);
        var service = new MonsterDropService(drops, catalog, new Random(1));
        return new Fixture(killer, mate, stranger, monster, drops, service);
    }

    private static ItemData sword() {
        var sword = new ItemData(1302000);
        sword.setSlotMax(1);
        sword.putStat("watk", 30);
        sword.putStat("str", 7);
        sword.setEquipment(new EquipmentData("Wp", false, 0, 0, 0, 0, 0, 0, 2, false, false, 5));
        return sword;
    }

    private static List<Spawn> spawns(GameplayTestSession session) {
        List<Spawn> result = new ArrayList<>();
        for (OutPacket packet : session.sent) {
            var wire = new ByteArrayInPacket(packet.getBytes());
            if (wire.readUnsignedShort() != SendOpcode.DROP_ITEM_FROM_MAPOBJECT.getValue()) {
                continue;
            }
            assertThat(wire.readByte()).isEqualTo((byte) 2);
            int oid = wire.readInt();
            boolean meso = wire.readByte() == 1;
            int value = wire.readInt();
            wire.readInt();
            wire.readByte();
            wire.readShort();
            wire.readShort();
            int owner = wire.readInt();
            result.add(new Spawn(oid, meso, value, owner));
        }
        return result;
    }

    private record Spawn(int oid, boolean meso, int value, int owner) {
    }

    private record Fixture(GameplayTestSession killer, GameplayTestSession mate, GameplayTestSession stranger,
                           MapleMonster monster, GroundDropService drops, MonsterDropService service) {
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        private void advance(long delta) {
            millis += delta;
        }

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override public long millis() {
            return millis;
        }
    }
}
