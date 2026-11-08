package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.item.EquipmentData;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.item.ScrollData;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.logic.game.DefaultEquipmentSystem;
import org.gms.logic.game.DefaultItemSystem;
import org.gms.logic.game.DefaultProgressionSystem;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 纯白/混沌/饰品卷轴与匠人之魂对背包内装备砸卷的频道回归。 */
public class SpecialScrollGameplayTest {
    private static final int SWORD = 1_302_000;
    private static final int RING = 1_112_400;
    private static final int WEAPON_SCROLL = 2_043_000;
    private static final int CLEAN_SLATE_SCROLL = 2_049_000;
    private static final int CHAOS_SCROLL = 2_049_100;
    private static final int ACCESSORY_SCROLL = 2_049_200;
    private static final int WHITE_SCROLL = 2_340_000;

    private final DefaultVersionGate versions = new DefaultVersionGate();
    private final PlayerSessionRegistry sessions = new PlayerSessionRegistry();
    private final MapleMap map = new MapleMap();
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);
    private final GameDataProvider data = data();
    private final DefaultItemSystem items = new DefaultItemSystem(versions, data);
    private final EquipmentService equipment = new EquipmentService(
            new DefaultEquipmentSystem(new DefaultProgressionSystem(versions)::accepts, data), sessions, clock);
    private final ScrollHandler handler = new ScrollHandler(equipment, sessions);

    @Test public void cleanSlateRestoresUpgradeSlotWithoutChangingLevelOrStats() {
        var owner = session(1);
        equip(owner, SWORD, (short) -11);
        Equip sword = equipped(owner, (short) -11);
        sword.setUpgradeSlots((byte) 3);
        sword.setLevel((short) 1);
        short scrollSlot = give(owner, CLEAN_SLATE_SCROLL);
        assertThat(items.giveItem(owner.character, WHITE_SCROLL, 1)).isTrue();

        handler.handle(owner, request(scrollSlot, (short) -11, true));

        assertThat(sword.getUpgradeSlots()).isEqualTo((byte) 4);
        assertThat(sword.getLevel()).isEqualTo((short) 1);
        assertThat(sword.getWAtk()).isEqualTo((short) 30);
        assertThat(owner.character.getItemCount(WHITE_SCROLL)).isOne();
        assertEffect(owner, 1, 0, 0, 0);
    }

    @Test public void cleanSlateRejectsWhenNoUpgradeSlotWasLost() {
        var owner = session(1);
        equip(owner, SWORD, (short) -11);
        short scrollSlot = give(owner, CLEAN_SLATE_SCROLL);

        handler.handle(owner, request(scrollSlot, (short) -11, false));

        assertThat(owner.character.getItemCount(CLEAN_SLATE_SCROLL)).isOne();
        assertThat(equipped(owner, (short) -11).getUpgradeSlots()).isEqualTo((byte) 5);
        assertThat(owner.sent).noneMatch(packet -> opcode(packet) == SendOpcode.SHOW_SCROLL_EFFECT.getValue());
    }

    @Test public void chaosScrollRandomizesPositiveStatsWithinRange() {
        var owner = session(1);
        equip(owner, SWORD, (short) -11);
        short scrollSlot = give(owner, CHAOS_SCROLL);

        handler.handle(owner, request(scrollSlot, (short) -11, false));

        Equip sword = equipped(owner, (short) -11);
        assertThat(owner.character.getItemCount(CHAOS_SCROLL)).isZero();
        assertThat(sword.getUpgradeSlots()).isEqualTo((byte) 4);
        assertThat(sword.getLevel()).isEqualTo((short) 1);
        assertThat((int) sword.getWAtk()).isBetween(25, 35);
        assertThat((int) sword.getStrStat()).isBetween(2, 12);
        assertEffect(owner, 1, 0, 0, 0);
    }

    @Test public void legendarySpiritAllowsScrollOnInventoryEquipment() {
        var owner = session(1);
        short equipSlot = give(owner, SWORD);
        owner.character.putSkill(new SkillEntry(1003, 1));
        short scrollSlot = give(owner, WEAPON_SCROLL);

        handler.handle(owner, request(scrollSlot, equipSlot, false));

        Equip sword = (Equip) owner.character.getInventory(InventoryType.EQUIP).getItem(equipSlot);
        assertThat(sword.getUpgradeSlots()).isEqualTo((byte) 4);
        assertThat(sword.getLevel()).isEqualTo((short) 1);
        assertThat(sword.getWAtk()).isEqualTo((short) 32);
        assertThat(owner.character.getItemCount(WEAPON_SCROLL)).isZero();
        assertEffect(owner, 1, 0, 1, 0);
    }

    @Test public void inventoryEquipmentIsRejectedWithoutLegendarySpirit() {
        var owner = session(1);
        short equipSlot = give(owner, SWORD);
        short scrollSlot = give(owner, WEAPON_SCROLL);

        handler.handle(owner, request(scrollSlot, equipSlot, false));

        Equip sword = (Equip) owner.character.getInventory(InventoryType.EQUIP).getItem(equipSlot);
        assertThat(sword.getUpgradeSlots()).isEqualTo((byte) 5);
        assertThat(sword.getWAtk()).isEqualTo((short) 30);
        assertThat(owner.character.getItemCount(WEAPON_SCROLL)).isOne();
        assertThat(owner.sent).noneMatch(packet -> opcode(packet) == SendOpcode.SHOW_SCROLL_EFFECT.getValue());
    }

    @Test public void accessoryScrollAppliesToRingButRejectsWeaponCategory() {
        var owner = session(1);
        equip(owner, RING, (short) -12);
        short accessorySlot = give(owner, ACCESSORY_SCROLL);
        handler.handle(owner, request(accessorySlot, (short) -12, false));

        Equip ring = equipped(owner, (short) -12);
        assertThat(ring.getUpgradeSlots()).isEqualTo((byte) 4);
        assertThat(ring.getLevel()).isEqualTo((short) 1);
        assertThat(ring.getStrStat()).isEqualTo((short) 6);

        equip(owner, SWORD, (short) -11);
        short weaponSlot = give(owner, ACCESSORY_SCROLL);
        handler.handle(owner, request(weaponSlot, (short) -11, false));
        assertThat(owner.character.getItemCount(ACCESSORY_SCROLL)).isOne();
        assertThat(equipped(owner, (short) -11).getUpgradeSlots()).isEqualTo((byte) 5);
    }

    private GameplayTestSession session(long id) {
        var session = new GameplayTestSession(id, map);
        sessions.claim(id, session);
        return session;
    }

    private void equip(GameplayTestSession owner, int itemId, short slot) {
        short source = give(owner, itemId);
        assertThat(equipment.move(owner, source, slot, 1)).isTrue();
    }

    private short give(GameplayTestSession owner, int itemId) {
        assertThat(items.giveItem(owner.character, itemId, 1)).isTrue();
        return slotOf(owner.character, InventoryType.getByType((byte) (itemId / 1000000)), itemId);
    }

    private static Equip equipped(GameplayTestSession owner, short slot) {
        return (Equip) owner.character.getInventory(InventoryType.EQUIP).getItem(slot);
    }

    private static short slotOf(PlayerCharacter character, InventoryType type, int itemId) {
        for (Item item : character.getInventory(type).items()) {
            if (item.getId() == itemId) {
                return item.getPosition();
            }
        }
        throw new AssertionError("未找到物品 " + itemId);
    }

    private static ByteArrayInPacket request(short scrollSlot, short equipSlot, boolean whiteScroll) {
        var packet = new ByteArrayOutPacket();
        packet.writeInt(0).writeShort(scrollSlot).writeShort(equipSlot).writeShort(whiteScroll ? 2 : 0);
        return new ByteArrayInPacket(packet.getBytes());
    }

    private static void assertEffect(GameplayTestSession session, int success, int cursed,
                                     int legendarySpirit, int whiteScroll) {
        OutPacket effect = session.sent.stream()
                .filter(packet -> opcode(packet) == SendOpcode.SHOW_SCROLL_EFFECT.getValue())
                .findFirst().orElseThrow();
        var wire = new ByteArrayInPacket(effect.getBytes());
        assertThat(wire.readUnsignedShort()).isEqualTo(SendOpcode.SHOW_SCROLL_EFFECT.getValue());
        assertThat(wire.readInt()).isEqualTo((int) session.character.getId());
        assertThat(wire.readByte()).isEqualTo((byte) success);
        assertThat(wire.readByte()).isEqualTo((byte) cursed);
        assertThat(wire.readByte()).isEqualTo((byte) legendarySpirit);
        assertThat(wire.readByte()).isEqualTo((byte) whiteScroll);
        assertThat(wire.available()).isZero();
    }

    private static int opcode(OutPacket packet) {
        return new ByteArrayInPacket(packet.getBytes()).readUnsignedShort();
    }

    private static GameDataProvider data() {
        ItemData sword = equipment(SWORD, "Wp", 5);
        sword.putStat("str", 7);
        sword.putStat("watk", 30);
        ItemData ring = equipment(RING, "Ri", 5);
        ring.putStat("str", 3);
        return GameDataProvider.fixed(Map.ofEntries(
                Map.entry(SWORD, sword),
                Map.entry(RING, ring),
                Map.entry(WEAPON_SCROLL, scroll(WEAPON_SCROLL, 100, 0, Map.of("watk", 2))),
                Map.entry(CLEAN_SLATE_SCROLL, scroll(CLEAN_SLATE_SCROLL, 100, 0, Map.of())),
                Map.entry(CHAOS_SCROLL, scroll(CHAOS_SCROLL, 100, 0, Map.of())),
                Map.entry(ACCESSORY_SCROLL, scroll(ACCESSORY_SCROLL, 100, 0, Map.of("str", 3))),
                Map.entry(WHITE_SCROLL, new ItemData(WHITE_SCROLL))), Map.of());
    }

    private static ItemData equipment(int itemId, String slot, int upgradeSlots) {
        ItemData data = new ItemData(itemId);
        data.setSlotMax(1);
        data.setEquipment(new EquipmentData(slot, false, 0, 0, 0, 0, 0, 0, 2, false, false, upgradeSlots));
        return data;
    }

    private static ItemData scroll(int itemId, int success, int cursed, Map<String, Integer> stats) {
        ItemData data = new ItemData(itemId);
        data.setSlotMax(100);
        data.setScroll(new ScrollData(success, cursed, Set.of(), stats));
        return data;
    }
}
