package org.gms.replaceable;

import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.item.ItemData;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物品系统：给/扣/查（经 CharacterState 接口，可替换层）+ 版本门拒绝迟到写。
 */
class ItemSystemTest {

    private final VersionGate versionGate = new DefaultVersionGate();
    private final Map<Integer, ItemData> itemData = Map.of(
            2_000_000, item(2_000_000, 100),
            2_000_001, item(2_000_001, 5));
    private final ItemSystem system = new ItemSystem(versionGate, itemData);

    private static ItemData item(int id, int slotMax) {
        ItemData d = new ItemData(id);
        d.setSlotMax(slotMax);
        return d;
    }

    @Test
    @DisplayName("给物品：堆叠 + 分配空槽")
    void giveItemAddsAndStacks() {
        Character chr = new Character(versionGate.currentVersion());

        assertThat(system.giveItem(chr, 2_000_000, 150)).isTrue();
        assertThat(system.countItem(chr, 2_000_000)).isEqualTo(150);
        // 100 + 50 → 两个槽
        assertThat(chr.getInventory(InventoryType.USE).size()).isEqualTo(2);
    }

    @Test
    @DisplayName("给物品：尊重 slotMax 低堆叠")
    void giveItemRespectsSlotMax() {
        Character chr = new Character(versionGate.currentVersion());

        assertThat(system.giveItem(chr, 2_000_001, 12)).isTrue();
        // 5 + 5 + 2 → 三个槽
        assertThat(chr.getInventory(InventoryType.USE).size()).isEqualTo(3);
        assertThat(system.countItem(chr, 2_000_001)).isEqualTo(12);
    }

    @Test
    @DisplayName("给物品：空间不足返回 false 且不动")
    void giveItemRejectsWhenNoSpace() {
        Character chr = new Character(versionGate.currentVersion());
        chr.getInventory(InventoryType.USE);          // 触发 USE 背包（槽位上限 24）
        // 塞满 24 槽（每槽 100）
        assertThat(system.giveItem(chr, 2_000_000, 24 * 100)).isTrue();
        assertThat(system.countItem(chr, 2_000_000)).isEqualTo(2400);

        assertThat(system.giveItem(chr, 2_000_000, 1)).isFalse();
        assertThat(system.countItem(chr, 2_000_000)).isEqualTo(2400);  // 未变
    }

    @Test
    @DisplayName("扣物品：部分扣/全扣/不足拒绝")
    void takeItemDeducts() {
        Character chr = new Character(versionGate.currentVersion());
        system.giveItem(chr, 2_000_000, 100);

        assertThat(system.takeItem(chr, 2_000_000, 30)).isTrue();
        assertThat(system.countItem(chr, 2_000_000)).isEqualTo(70);

        assertThat(system.takeItem(chr, 2_000_000, 71)).isFalse();     // 不足，不动
        assertThat(system.countItem(chr, 2_000_000)).isEqualTo(70);

        assertThat(system.takeItem(chr, 2_000_000, 70)).isTrue();
        assertThat(system.countItem(chr, 2_000_000)).isZero();
    }

    @Test
    @DisplayName("版本门拒绝换代后的迟到写")
    void versionGateBlocksStaleWrite() {
        Character chr = new Character(versionGate.currentVersion());
        versionGate.onReload();                       // 逻辑换代

        assertThat(system.giveItem(chr, 2_000_000, 5)).isFalse();
        assertThat(system.takeItem(chr, 2_000_000, 1)).isFalse();
        assertThat(system.countItem(chr, 2_000_000)).isZero();
    }
}
