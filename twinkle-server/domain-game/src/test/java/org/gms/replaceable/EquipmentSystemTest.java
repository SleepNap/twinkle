package org.gms.replaceable;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.item.EquipmentData;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.spi.EquipmentState.SlotMove;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证换装事务、穿戴条件与派生属性；测试装备和数值独立构造。 */
public class EquipmentSystemTest {
    private final DefaultVersionGate versions = new DefaultVersionGate();
    private final Map<Integer, ItemData> definitions = new HashMap<>();

    @Test public void createsTemplateEquipmentAndMovesExactInstancesWithoutAccumulatingStats() {
        ItemData sword = definition(1302000, "Wp");
        sword.putStat("watk", 27); sword.putStat("str", 5); sword.putStat("hp", 40);
        PlayerCharacter player = player();
        ItemSystem items = items();
        EquipmentSystem equipment = equipment();
        assertThat(items.giveItem(player, 1302000, 2)).isTrue();
        Equip first = gear(player, 1), second = gear(player, 2);
        assertThat(first.getWAtk()).isEqualTo((short) 27);
        first.setOwner("独立实例"); first.setGiftFrom("赠送者"); first.setItemExp(456);
        first.setWAtk((short) 35); second.setStrStat((short) 9);
        player.clearDirty();
        assertThat(move(equipment, player, 1, -11)).isNotNull();
        assertThat(player.isDirty()).isTrue();
        assertThat(gear(player, -11)).isSameAs(first);
        assertThat(player.totalStr()).isEqualTo(15);
        assertThat(player.equipmentStats().weaponAttack()).isEqualTo(35);
        assertThat(player.getStrStat()).isEqualTo((short) 10);
        assertThat(player.effectiveMaxHp()).isEqualTo(140);
        assertThat(move(equipment, player, 2, -11)).isNotNull();
        assertThat(gear(player, 2)).isSameAs(first);
        assertThat(gear(player, 2).getOwner()).isEqualTo("独立实例");
        assertThat(gear(player, 2).getGiftFrom()).isEqualTo("赠送者");
        assertThat(gear(player, 2).getItemExp()).isEqualTo(456);
        assertThat(player.totalStr()).isEqualTo(19);
        for (int i = 0; i < 3; i++) {
            assertThat(move(equipment, player, -11, 1)).isNotNull();
            assertThat(player.totalStr()).isEqualTo(10);
            assertThat(move(equipment, player, 1, -11)).isNotNull();
            assertThat(player.totalStr()).isEqualTo(19);
        }
        assertThat(player.getMaxHp()).isEqualTo(100);
    }

    @Test public void conflictingWeaponAndShieldAreAtomicWhenTheBagIsFull() {
        definition(1302000, "Wp"); definition(1402000, "WpSi");
        definition(1092000, "Si"); definition(1002000, "Cp");
        var player = player(); var items = items(); var equipment = equipment();
        items.giveItem(player, 1302000, 1); move(equipment, player, 1, -11);
        items.giveItem(player, 1092000, 1); move(equipment, player, 1, -10);
        items.giveItem(player, 1402000, 1); items.giveItem(player, 1002000, 23);
        var before = player.equipmentItems(); player.clearDirty();
        assertThat(move(equipment, player, 1, -11)).isNull();
        assertThat(player.equipmentItems()).isEqualTo(before);
        assertThat(player.isDirty()).isFalse();
        player.getInventory(InventoryType.EQUIP).removeItem((short) 2);
        assertThat(move(equipment, player, 1, -11)).isNotNull();
        assertThat(gear(player, -11).getId()).isEqualTo(1402000);
        assertThat(gear(player, 1).getId()).isEqualTo(1302000);
        assertThat(gear(player, 2).getId()).isEqualTo(1092000);
        assertThat(gear(player, -10)).isNull();
        // 反向穿盾也必须腾出位置放双手武器，原盾源槽可以复用。
        assertThat(move(equipment, player, 2, -10)).isNotNull();
        assertThat(gear(player, -11)).isNull();
        assertThat(gear(player, 2).getId()).isEqualTo(1402000);
    }

    @Test public void overallAndPantsDisplaceEachOtherUsingTheFreedSourceSlot() {
        definition(1052000, "MaPn"); definition(1062000, "Pn");
        var player = player(); var items = items(); var equipment = equipment();
        items.giveItem(player, 1062000, 1); move(equipment, player, 1, -6);
        items.giveItem(player, 1052000, 1);
        assertThat(move(equipment, player, 1, -5)).isNotNull();
        assertThat(gear(player, -6)).isNull();
        assertThat(gear(player, 1).getId()).isEqualTo(1062000);
        assertThat(move(equipment, player, 1, -6)).isNotNull();
        assertThat(gear(player, -5)).isNull();
        assertThat(gear(player, 1).getId()).isEqualTo(1052000);
    }

    @Test public void requirementsExcludeTheCandidateAndDisplacedEquipment() {
        var old = definition(1302000, "Wp"); old.putStat("str", 50);
        var replacement = definition(1302001, "Wp"); replacement.putStat("str", 80);
        replacement.setReqLevel(20);
        replacement.setEquipment(new EquipmentData("Wp", false, 1, 20, 10, 10, 10, 5, 0, false, false, 0));
        var ring = definition(1112000, "Ri"); ring.putStat("str", 15);
        var player = player(); var items = items(); var equipment = equipment();
        items.giveItem(player, 1302000, 1); move(equipment, player, 1, -11);
        items.giveItem(player, 1302001, 1);
        assertThat(player.totalStr()).isEqualTo(60);
        assertThat(move(equipment, player, 1, -11)).isNull();
        items.giveItem(player, 1112000, 1); move(equipment, player, 2, -12);
        player.setJob(200);
        assertThat(move(equipment, player, 1, -11)).isNull();
        player.setJob(1100); player.setLevel(19);
        assertThat(move(equipment, player, 1, -11)).isNull();
        player.setLevel(20); player.setGender(1);
        assertThat(move(equipment, player, 1, -11)).isNull();
        player.setGender(0); player.setFame(4);
        assertThat(move(equipment, player, 1, -11)).isNull();
        player.setFame(5);
        assertThat(move(equipment, player, 1, -11)).isNotNull();
        assertThat(player.totalStr()).isEqualTo(105);
    }

    @Test public void forgedSlotsExpirationQuantitiesAndStaleVersionsCannotMutateEquipment() {
        definition(1002000, "Cp");
        var player = player(); var equipment = equipment(); items().giveItem(player, 1002000, 1);
        var before = player.equipmentItems();
        assertThat(move(equipment, player, 1, -11)).isNull();
        assertThat(move(equipment, player, 1, -101)).isNull();
        assertThat(move(equipment, player, 1, Short.MIN_VALUE)).isNull();
        assertThat(equipment.move(player, (short) 1, (short) -1, 2, 1000)).isNull();
        assertThat(equipment.move(player, (short) 1, (short) -1, -1, 1000)).isNull();
        assertThat(player.equipmentItems()).isEqualTo(before);
        gear(player, 1).setExpiration(0);
        assertThat(move(equipment, player, 1, -1)).isNull();
        gear(player, 1).setExpiration(1000);
        assertThat(move(equipment, player, 1, -1)).isNull();
        gear(player, 1).setExpiration(-1);
        player.setHp(0);
        assertThat(move(equipment, player, 1, -1)).isNull();
        player.setHp(50); versions.onReload();
        assertThat(move(equipment, player, 1, -1)).isNull();
        assertThat(gear(player, -1)).isNull();
    }

    @Test public void cashAppearanceAddsNoStatsAndUniqueRingsCannotBeEquippedTwice() {
        var cash = definition(1002000, "Cp");
        cash.setEquipment(new EquipmentData("Cp", true, 0, 0, 0, 0, 0, 0, 2, false, false, 0));
        var ring = definition(1112000, "Ri");
        ring.setEquipment(new EquipmentData("Ri", false, 0, 0, 0, 0, 0, 0, 2, true, true, 0));
        var player = player(); var equipment = equipment();
        Equip hat = new Equip(1002000); hat.setPosition((short) 1); hat.setStrStat((short) 500);
        hat.setCashId(71); player.getInventory(InventoryType.EQUIP).putAtSlot((short) 1, hat);
        assertThat(move(equipment, player, 1, -1)).isNull();
        assertThat(move(equipment, player, 1, -101)).isNotNull();
        assertThat(player.totalStr()).isEqualTo(10);
        items().giveItem(player, 1112000, 2);
        var changed = move(equipment, player, 1, -12);
        assertThat(changed.boundSlots()).containsExactly((short) -12);
        assertThat(gear(player, -12).getFlag() & 8).isEqualTo(8);
        assertThat(move(equipment, player, 2, -13)).isNull();
        assertThat(move(equipment, player, -12, 1)).isNotNull();
        assertThat(gear(player, 1).getFlag() & 8).isEqualTo(8);
    }

    @Test public void healingUsesEquipmentMaximumAndUnequipClampsWithoutRevivingOrChangingBaseStats() {
        var hat = definition(1002000, "Cp"); hat.putStat("hp", 100); hat.putStat("mp", 50);
        var potion = new ItemData(2000000); potion.putStat("hpr", 100); potion.putStat("mpr", 100);
        definitions.put(2000000, potion);
        var player = player(); var equipment = equipment(); var items = items();
        items.giveItem(player, 1002000, 1); move(equipment, player, 1, -1);
        items.giveItem(player, 2000000, 1);
        assertThat(items.consumeRecovery(player, (short) 1, 2000000, 1000)).isTrue();
        assertThat(player.getHp()).isEqualTo(200);
        assertThat(player.getMp()).isEqualTo(100);
        assertThat(move(equipment, player, -1, 1)).isNotNull();
        assertThat(player.getHp()).isEqualTo(100);
        assertThat(player.getMp()).isEqualTo(50);
        assertThat(player.getMaxHp()).isEqualTo(100);
        move(equipment, player, 1, -1);
        gear(player, -1).setExpiration(1001); player.setHp(0);
        assertThat(equipment.expire(player, 1001)).isTrue();
        assertThat(equipment.refresh(player, 1001)).isTrue();
        assertThat(gear(player, -1)).isNull();
        assertThat(player.getHp()).isZero();
        assertThat(player.effectiveMaxHp()).isEqualTo(100);
    }

    @Test public void staleInstanceSnapshotAndInvalidMovePlanAreRejectedBeforeAnyWrite() {
        definition(1002000, "Cp"); var player = player(); items().giveItem(player, 1002000, 1);
        var before = player.equipmentItems();
        gear(player, 1).setOwner("已变更"); player.clearDirty();
        assertThat(player.applyEquipmentMoves(before, List.of(new SlotMove((short) 1, (short) -1)), Set.of())).isFalse();
        var current = player.equipmentItems();
        assertThat(player.applyEquipmentMoves(current, List.of(new SlotMove((short) 1, (short) -1),
                new SlotMove((short) 99, (short) -2)), Set.of())).isFalse();
        assertThat(player.equipmentItems()).isEqualTo(current);
        assertThat(player.isDirty()).isFalse();
    }

    @Test public void genericItemRemovalCannotConsumeEquippedGearOrPartiallyConsumeTheBag() {
        definition(1002000, "Cp"); var player = player(); var items = items();
        items.giveItem(player, 1002000, 2); move(equipment(), player, 1, -1);
        var before = player.equipmentItems(); player.clearDirty();
        assertThat(items.takeItem(player, 1002000, 2)).isFalse();
        assertThat(player.equipmentItems()).isEqualTo(before);
        assertThat(player.isDirty()).isFalse();
        assertThat(items.takeItem(player, 1002000, 1)).isTrue();
        assertThat(gear(player, -1)).isNotNull();
        assertThat(gear(player, 2)).isNull();
    }

    private ItemData definition(int id, String slot) {
        ItemData item = new ItemData(id); item.setSlotMax(1);
        item.setEquipment(new EquipmentData(slot, false, 0, 0, 0, 0, 0, 0, 2, false, false, 5));
        definitions.put(id, item); return item;
    }
    private ItemSystem items() { return new ItemSystem(versions, GameDataProvider.fixed(definitions, Map.of())); }
    private EquipmentSystem equipment() {
        return new EquipmentSystem(new ProgressionSystem(versions)::accepts, GameDataProvider.fixed(definitions, Map.of()));
    }
    private static PlayerCharacter player() {
        var player = new PlayerCharacter(1);
        player.setLevel(30); player.setJob(100); player.setFame(10); player.setGender(0);
        player.setStrStat((short) 10); player.setDexStat((short) 10);
        player.setIntStat((short) 10); player.setLukStat((short) 10);
        player.setHp(50); player.setMaxHp(100); player.setMp(20); player.setMaxMp(50);
        return player;
    }
    private static EquipmentSystem.Change move(EquipmentSystem equipment, PlayerCharacter player, int source, int target) {
        return equipment.move(player, (short) source, (short) target, 0, 1000);
    }
    private static Equip gear(PlayerCharacter player, int slot) {
        return (Equip) player.getInventory(InventoryType.EQUIP).getItem((short) slot);
    }
}
