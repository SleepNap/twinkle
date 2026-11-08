package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.item.EquipmentData;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.item.ScrollData;
import org.gms.domain.game.map.MapleMap;
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

/** 升级卷轴从收包、结算、扣物品到效果广播的频道回归。 */
public class ScrollGameplayTest {
    private static final int SWORD = 1_302_000;
    private static final int NO_SLOT_SWORD = 1_302_001;
    private static final int HAT = 1_002_000;
    private static final int ATTACK_SCROLL = 2_043_000;
    private static final int FAIL_SCROLL = 2_043_001;
    private static final int CURSED_SCROLL = 2_043_002;
    private static final int HELMET_SCROLL = 2_040_000;
    private static final int RESTRICTED_SCROLL = 2_043_003;
    private static final int UNSUPPORTED_SCROLL = 2_049_300;
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

    @Test public void successfulScrollImprovesEquipmentConsumesScrollAndBroadcastsEffect() {
        var owner = session(1);
        var observer = session(2);
        equip(owner, SWORD);
        short scrollSlot = giveScroll(owner, ATTACK_SCROLL);
        owner.sent.clear();
        observer.sent.clear();

        handler.handle(owner, request(scrollSlot, (short) -11, false));

        assertThat(owner.character.getItemCount(ATTACK_SCROLL)).isZero();
        Equip equipped = equipped(owner);
        assertThat(equipped.getUpgradeSlots()).isEqualTo((byte) 4);
        assertThat(equipped.getLevel()).isEqualTo((short) 1);
        assertThat(equipped.getWAtk()).isEqualTo((short) 32);
        assertThat(owner.character.equipmentStats().weaponAttack()).isEqualTo(32);
        assertThat(owner.sent.stream()
                .filter(packet -> opcode(packet) == SendOpcode.INVENTORY_OPERATION.getValue())).hasSize(2);
        assertThat(observer.sent).anyMatch(packet -> opcode(packet) == SendOpcode.SHOW_SCROLL_EFFECT.getValue());
        assertEffect(effectOf(owner), owner.character.getId(), 1, 0, 0);

        var saved = new PlayerCharacterAssembler(versions).toInventoryData(owner.character).stream()
                .filter(item -> item.getPosition() == -11).findFirst().orElseThrow();
        assertThat(saved.getUpgradeSlots()).isEqualTo(4);
        assertThat(saved.getLevel()).isEqualTo(1);
        assertThat(saved.getWAtk()).isEqualTo(32);
    }

    @Test public void failedScrollConsumesUpgradeSlotAndCursedScrollDestroysTheEquippedItem() {
        var owner = session(1);
        var observer = session(2);
        equip(owner, SWORD);
        short failSlot = giveScroll(owner, FAIL_SCROLL);
        handler.handle(owner, request(failSlot, (short) -11, false));

        Equip failed = equipped(owner);
        assertThat(failed.getUpgradeSlots()).isEqualTo((byte) 4);
        assertThat(failed.getLevel()).isZero();
        assertThat(failed.getWAtk()).isEqualTo((short) 30);
        assertEffect(effectOf(owner), owner.character.getId(), 0, 0, 0);

        owner.sent.clear();
        observer.sent.clear();
        short cursedSlot = giveScroll(owner, CURSED_SCROLL);
        handler.handle(owner, request(cursedSlot, (short) -11, false));

        assertThat(owner.character.getInventory(InventoryType.EQUIP).getItem((short) -11)).isNull();
        assertThat(owner.character.equipmentStats().weaponAttack()).isZero();
        assertEffect(effectOf(owner), owner.character.getId(), 0, 1, 0);
        assertThat(observer.sent).anyMatch(packet -> opcode(packet) == SendOpcode.UPDATE_CHAR_LOOK.getValue());
    }

    @Test public void whiteScrollKeepsUpgradeSlotOnFailureAndIsConsumed() {
        var owner = session(1);
        equip(owner, SWORD);
        short scrollSlot = giveScroll(owner, FAIL_SCROLL);
        assertThat(items.giveItem(owner.character, WHITE_SCROLL, 1)).isTrue();

        handler.handle(owner, request(scrollSlot, (short) -11, true));

        Equip equipped = equipped(owner);
        assertThat(equipped.getUpgradeSlots()).isEqualTo((byte) 5);
        assertThat(equipped.getWAtk()).isEqualTo((short) 30);
        assertThat(owner.character.getItemCount(WHITE_SCROLL)).isZero();
        assertEffect(effectOf(owner), owner.character.getId(), 0, 0, 1);
    }

    @Test public void requestedWhiteScrollWithoutInventoryFallsBackToNormalFailure() {
        var owner = session(1);
        equip(owner, SWORD);
        short scrollSlot = giveScroll(owner, FAIL_SCROLL);

        handler.handle(owner, request(scrollSlot, (short) -11, true));

        assertThat(equipped(owner).getUpgradeSlots()).isEqualTo((byte) 4);
        assertEffect(effectOf(owner), owner.character.getId(), 0, 0, 0);
    }

    @Test public void rejectsInvalidRequestsWithoutConsumingItemsOrChangingEquipment() {
        var owner = session(1);
        equip(owner, SWORD);
        owner.sent.clear();
        var before = owner.character.equipmentItems();

        short helmet = giveScroll(owner, HELMET_SCROLL);
        handler.handle(owner, request(helmet, (short) -11, false));
        assertThat(owner.character.getItemCount(HELMET_SCROLL)).isOne();
        assertEmptyInventoryOperation(owner);

        short unsupported = giveScroll(owner, UNSUPPORTED_SCROLL);
        handler.handle(owner, request(unsupported, (short) -11, false));
        assertThat(owner.character.getItemCount(UNSUPPORTED_SCROLL)).isOne();

        short restricted = giveScroll(owner, RESTRICTED_SCROLL);
        handler.handle(owner, request(restricted, (short) -11, false));
        assertThat(owner.character.getItemCount(RESTRICTED_SCROLL)).isOne();

        handler.handle(owner, new ByteArrayInPacket(new byte[6]));
        handler.handle(owner, request(helmet, (short) 1, false));
        assertThat(owner.character.equipmentItems()).isEqualTo(before);
        assertThat(owner.sent).noneMatch(packet -> opcode(packet) == SendOpcode.SHOW_SCROLL_EFFECT.getValue());

        assertThat(items.giveItem(owner.character, NO_SLOT_SWORD, 1)).isTrue();
        short noSlot = slotOf(owner.character, InventoryType.EQUIP, NO_SLOT_SWORD);
        assertThat(equipment.move(owner, noSlot, (short) -11, 1)).isTrue();
        short attack = giveScroll(owner, ATTACK_SCROLL);
        handler.handle(owner, request(attack, (short) -11, false));
        assertThat(owner.character.getItemCount(ATTACK_SCROLL)).isOne();

        equipped(owner).setExpiration(500);
        handler.handle(owner, request(attack, (short) -11, false));
        assertThat(owner.character.getItemCount(ATTACK_SCROLL)).isOne();
        assertThat(equipped(owner).getUpgradeSlots()).isZero();
    }

    private GameplayTestSession session(long id) {
        var session = new GameplayTestSession(id, map);
        sessions.claim(id, session);
        return session;
    }

    private void equip(GameplayTestSession owner, int itemId) {
        assertThat(items.giveItem(owner.character, itemId, 1)).isTrue();
        short slot = slotOf(owner.character, InventoryType.EQUIP, itemId);
        assertThat(equipment.move(owner, slot, (short) -11, 1)).isTrue();
    }

    private short giveScroll(GameplayTestSession owner, int itemId) {
        assertThat(items.giveItem(owner.character, itemId, 1)).isTrue();
        return slotOf(owner.character, InventoryType.USE, itemId);
    }

    private static Equip equipped(GameplayTestSession owner) {
        return (Equip) owner.character.getInventory(InventoryType.EQUIP).getItem((short) -11);
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

    private static void assertEffect(ByteArrayInPacket effect, long characterId,
                                     int success, int cursed, int whiteScroll) {
        assertThat(effect.readUnsignedShort()).isEqualTo(SendOpcode.SHOW_SCROLL_EFFECT.getValue());
        assertThat(effect.readInt()).isEqualTo((int) characterId);
        assertThat(effect.readByte()).isEqualTo((byte) success);
        assertThat(effect.readByte()).isEqualTo((byte) cursed);
        assertThat(effect.readByte()).isZero();  // 匠人之魂未接入
        assertThat(effect.readByte()).isEqualTo((byte) whiteScroll);
        assertThat(effect.available()).isZero();
    }

    private static ByteArrayInPacket effectOf(GameplayTestSession session) {
        OutPacket effect = session.sent.stream()
                .filter(packet -> opcode(packet) == SendOpcode.SHOW_SCROLL_EFFECT.getValue())
                .findFirst().orElseThrow();
        return new ByteArrayInPacket(effect.getBytes());
    }

    private static void assertEmptyInventoryOperation(GameplayTestSession session) {
        OutPacket empty = session.sent.stream()
                .filter(packet -> opcode(packet) == SendOpcode.INVENTORY_OPERATION.getValue())
                .findFirst().orElseThrow();
        var operation = new ByteArrayInPacket(empty.getBytes());
        operation.readUnsignedShort();
        assertThat(operation.readByte()).isEqualTo((byte) 1);
        assertThat(operation.readByte()).isZero();
        assertThat(operation.available()).isZero();
    }

    private static int opcode(OutPacket packet) {
        return new ByteArrayInPacket(packet.getBytes()).readUnsignedShort();
    }

    private static GameDataProvider data() {
        ItemData sword = equipment(SWORD, "Wp", 5);
        sword.putStat("str", 7);
        sword.putStat("watk", 30);
        ItemData noSlot = equipment(NO_SLOT_SWORD, "Wp", 0);
        noSlot.putStat("watk", 10);
        ItemData hat = equipment(HAT, "Cp", 5);
        hat.putStat("wdef", 5);
        ItemData whiteScroll = new ItemData(WHITE_SCROLL);
        whiteScroll.setSlotMax(100);
        return GameDataProvider.fixed(Map.ofEntries(
                Map.entry(SWORD, sword),
                Map.entry(NO_SLOT_SWORD, noSlot),
                Map.entry(HAT, hat),
                Map.entry(ATTACK_SCROLL, scroll(ATTACK_SCROLL, 100, 0, Set.of(), Map.of("watk", 2))),
                Map.entry(FAIL_SCROLL, scroll(FAIL_SCROLL, 0, 0, Set.of(), Map.of("watk", 2))),
                Map.entry(CURSED_SCROLL, scroll(CURSED_SCROLL, 0, 100, Set.of(), Map.of("watk", 2))),
                Map.entry(HELMET_SCROLL, scroll(HELMET_SCROLL, 100, 0, Set.of(), Map.of("wdef", 1))),
                Map.entry(RESTRICTED_SCROLL, scroll(RESTRICTED_SCROLL, 100, 0, Set.of(HAT), Map.of("watk", 1))),
                Map.entry(UNSUPPORTED_SCROLL, scroll(UNSUPPORTED_SCROLL, 100, 0, Set.of(), Map.of("watk", 1))),
                Map.entry(WHITE_SCROLL, whiteScroll)), Map.of());
    }

    private static ItemData equipment(int itemId, String slot, int upgradeSlots) {
        ItemData data = new ItemData(itemId);
        data.setSlotMax(1);
        data.setEquipment(new EquipmentData(slot, false, 0, 0, 0, 0, 0, 0, 2, false, false, upgradeSlots));
        return data;
    }

    private static ItemData scroll(int itemId, int success, int cursed,
                                   Set<Integer> requiredItems, Map<String, Integer> stats) {
        ItemData data = new ItemData(itemId);
        data.setSlotMax(100);
        data.setScroll(new ScrollData(success, cursed, requiredItems, stats));
        return data;
    }
}
