package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.item.EquipmentData;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.persistence.entity.InventoryItemEntity;
import org.gms.persistence.repo.InventoryItemRepository;
import org.gms.replaceable.CombatSystem;
import org.gms.replaceable.EquipmentSystem;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.ProgressionSystem;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 装备从收包到槽位、状态、同屏回包及存档恢复的服务端回归。 */
public class EquipmentGameplayTest {
    private final DefaultVersionGate versions = new DefaultVersionGate();
    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();
    private final MapleMap map = new MapleMap();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);

    @Test public void itemMoveUsesSignedEquipmentSlotsAndSendsAppearanceOnlyToOtherVisiblePlayers() {
        var owner = session(1); var observer = session(2);
        var data = data(false); var items = new ItemSystem(versions, data);
        var service = service(data);
        try (var drops = new GroundDropService(items, data, sessions, clock)) {
            var handler = new InventoryMoveHandler(items, drops, service, sessions);
            items.giveItem(owner.character, 1302000, 1);
            handler.handle(owner, request(1, 1, 2, 0));
            assertThat(owner.character.getInventory(InventoryType.EQUIP).getItem((short) 2)).isInstanceOf(Equip.class);
            handler.handle(owner, request(1, 2, 1, 0));
            owner.sent.clear();
            Equip item = (Equip) owner.character.getInventory(InventoryType.EQUIP).getItem((short) 1);
            handler.handle(owner, request(1, 1, -11, 0));
            assertThat(owner.character.getInventory(InventoryType.EQUIP).getItem((short) -11)).isSameAs(item);
            var movement = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
            assertThat(movement.readUnsignedShort()).isEqualTo(SendOpcode.INVENTORY_OPERATION.getValue());
            assertThat(movement.readByte()).isEqualTo((byte) 1);
            assertThat(movement.readByte()).isEqualTo((byte) 1);
            assertThat(movement.readByte()).isEqualTo((byte) 2);
            assertThat(movement.readByte()).isEqualTo((byte) 1);
            assertThat(movement.readShort()).isEqualTo((short) 1);
            assertThat(movement.readShort()).isEqualTo((short) -11);
            assertThat(movement.readByte()).isEqualTo((byte) 2);
            assertThat(movement.available()).isZero();
            assertThat(observer.sent).hasSize(1);
            byte[] look = observer.sent.getFirst().getBytes();
            var changed = new ByteArrayInPacket(look);
            assertThat(changed.readUnsignedShort()).isEqualTo(SendOpcode.UPDATE_CHAR_LOOK.getValue());
            assertThat(changed.readInt()).isEqualTo(1);
            assertThat(changed.readByte()).isEqualTo((byte) 1);
            assertThat(Arrays.copyOfRange(look, look.length - 7, look.length)).containsOnly((byte) 0);
            assertThat(owner.sent).noneMatch(packet -> new ByteArrayInPacket(packet.getBytes()).readUnsignedShort()
                    == SendOpcode.UPDATE_CHAR_LOOK.getValue());
            owner.sent.clear(); owner.character.setHp(140);
            handler.handle(owner, request(1, -11, 1, 0));
            byte[] removed = owner.sent.getFirst().getBytes();
            assertThat(removed[removed.length - 1]).isEqualTo((byte) 1);
            assertThat(owner.character.getHp()).isEqualTo(100);
            assertThat(owner.character.getMaxHp()).isEqualTo(100);
            var health = new ByteArrayInPacket(owner.sent.get(1).getBytes());
            health.skip(3);
            assertThat(health.readInt()).isEqualTo(GameplayPackets.HP);
            assertThat(health.readShort()).isEqualTo((short) 100);
        }
    }

    @Test public void rejectsWrongInventoryOldConnectionsTransitionTradeAndTruncatedPackets() {
        var owner = session(1); var observer = session(2);
        var data = data(false); var items = new ItemSystem(versions, data);
        try (var drops = new GroundDropService(items, data, sessions, clock)) {
            var handler = new InventoryMoveHandler(items, drops, service(data), sessions);
            items.giveItem(owner.character, 1302000, 1);
            var before = owner.character.equipmentItems();
            handler.handle(owner, request(2, 1, -11, 0));
            handler.handle(owner, new ByteArrayInPacket(new byte[10]));
            owner.setAttr("trade", new Object()); handler.handle(owner, request(1, 1, -11, 0));
            owner.setAttr("trade", null); owner.setAttr("mapTransition", true);
            handler.handle(owner, request(1, 1, -11, 0)); owner.setAttr("mapTransition", null);
            session(1); observer.sent.clear();
            handler.handle(owner, request(1, 1, -11, 0));
            assertThat(owner.character.equipmentItems()).isEqualTo(before);
            assertThat(observer.sent).isEmpty();
            assertThat(owner.sent).allMatch(packet -> new ByteArrayInPacket(packet.getBytes()).readUnsignedShort()
                    == SendOpcode.STAT_CHANGED.getValue());
        }
    }

    @Test public void bindingUpdatesTheFullItemAndExpiredEquipmentIncludesRemovalRecalculationFlag() {
        var owner = session(1); var observer = session(2);
        var data = data(true); var items = new ItemSystem(versions, data); var service = service(data);
        items.giveItem(owner.character, 1302000, 1);
        owner.character.getInventory(InventoryType.EQUIP).getItem((short) 1).setExpiration(1500);
        assertThat(service.move(owner, (short) 1, (short) -11, 1)).isTrue();
        var packet = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
        packet.skip(3);
        assertThat(packet.readByte()).isEqualTo((byte) 3); // 移动、移除旧标志、添加完整新实例
        packet.skip(6);
        assertThat(packet.readByte()).isEqualTo((byte) 3);
        assertThat(packet.readByte()).isEqualTo((byte) 1);
        assertThat(packet.readShort()).isEqualTo((short) -11);
        assertThat(packet.readByte()).isEqualTo((byte) 0);
        assertThat(packet.readByte()).isEqualTo((byte) 1);
        assertThat(packet.readShort()).isEqualTo((short) -11);
        assertThat(owner.character.getInventory(InventoryType.EQUIP).getItem((short) -11).getFlag() & 8).isEqualTo(8);
        owner.sent.clear(); observer.sent.clear(); owner.character.setHp(140);
        new EquipmentService(new EquipmentSystem(new ProgressionSystem(versions)::accepts, data), sessions,
                Clock.fixed(Instant.ofEpochMilli(1500), ZoneOffset.UTC)).refresh(owner);
        assertThat(owner.character.getInventory(InventoryType.EQUIP).getItem((short) -11)).isNull();
        assertThat(owner.character.getHp()).isEqualTo(100);
        var expiry = new ByteArrayInPacket(owner.sent.getFirst().getBytes());
        expiry.skip(3);
        assertThat(expiry.readByte()).isEqualTo((byte) 1);
        assertThat(expiry.readByte()).isEqualTo((byte) 3);
        assertThat(expiry.readByte()).isEqualTo((byte) 1);
        assertThat(expiry.readShort()).isEqualTo((short) -11);
        assertThat(expiry.readByte()).isEqualTo((byte) 2);
        assertThat(expiry.available()).isZero();
        assertThat(observer.sent).hasSize(1);
    }

    @Test public void saveReloadPreservesBaseStatsAndRebuildsEquipmentBonusesFromTheSavedInstance() {
        var owner = session(1); var data = data(false); var items = new ItemSystem(versions, data);
        items.giveItem(owner.character, 1302000, 1);
        service(data).move(owner, (short) 1, (short) -11, 0);
        owner.character.setHp(140);
        var assembler = new PlayerCharacterAssembler(versions);
        var record = assembler.toData(owner.character);
        var inventory = assembler.toInventoryData(owner.character);
        assertThat(record.getMaxHp()).isEqualTo((short) 100);
        assertThat(record.getHp()).isEqualTo((short) 140);
        assertThat(record.getStrStat()).isEqualTo(owner.character.getStrStat());
        assertThat(inventory.getFirst().getPosition()).isEqualTo(-11);
        var repository = new InventoryItemRepository() {
            @Override public List<InventoryItemEntity> findByCharacterId(long id) { return inventory; }
            @Override public void insert(InventoryItemEntity item) { }
            @Override public void replaceAll(long id, List<InventoryItemEntity> items) { }
        };
        PlayerCharacter loaded = new PlayerCharacterAssembler(versions, repository).fromData(record);
        assertThat(loaded.equipmentStats().weaponAttack()).isZero();
        var connection = session(3);
        loaded.setMapObject(map); connection.setAttr("character", loaded); sessions.claim(loaded.getId(), connection);
        service(data).initialize(connection);
        assertThat(loaded.equipmentStats().weaponAttack()).isEqualTo(30);
        assertThat(loaded.totalStr()).isEqualTo(owner.character.totalStr());
        assertThat(loaded.effectiveMaxHp()).isEqualTo(140);
        assertThat(loaded.getHp()).isEqualTo(140);
        assertThat(loaded.getMaxHp()).isEqualTo(100);
        service(data).initialize(connection);
        assertThat(loaded.effectiveMaxHp()).isEqualTo(140);
    }

    @Test public void existingPhysicalDamageEntryReadsEquipmentStats() {
        var owner = session(1); var data = data(false);
        var items = new ItemSystem(versions, data);
        var monsterData = new MobData(1); monsterData.setMaxHp(10000);
        var monster = new MapleMonster(monsterData);
        var combat = new CombatSystem(versions);
        int bare = combat.physicalAttack(owner.character, monster, CombatSystem.BARE_HAND_WATK).damage();
        items.giveItem(owner.character, 1302000, 1);
        service(data).move(owner, (short) 1, (short) -11, 0);
        int equipped = combat.physicalAttack(owner.character, monster, owner.character.equipmentStats().weaponAttack()).damage();
        assertThat(equipped).isGreaterThan(bare);
    }

    private GameplayTestSession session(long id) {
        var session = new GameplayTestSession(id, map); sessions.claim(id, session); return session;
    }
    private GameDataProvider data(boolean binding) {
        ItemData sword = new ItemData(1302000);
        sword.setSlotMax(1); sword.putStat("str", 7); sword.putStat("hp", 40); sword.putStat("watk", 30);
        sword.setEquipment(new EquipmentData("Wp", false, 0, 0, 0, 0, 0, 0, 2, binding, false, 5));
        return GameDataProvider.fixed(Map.of(1302000, sword), Map.of());
    }
    private EquipmentService service(GameDataProvider data) {
        return new EquipmentService(new EquipmentSystem(new ProgressionSystem(versions)::accepts, data), sessions, clock);
    }
    private static ByteArrayInPacket request(int type, int source, int target, int quantity) {
        var packet = new ByteArrayOutPacket();
        packet.writeInt(0).writeByte(type).writeShort(source).writeShort(target).writeShort(quantity);
        return new ByteArrayInPacket(packet.getBytes());
    }
}
