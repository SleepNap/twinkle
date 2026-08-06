package org.gms.domain.game.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物品 id → 背包类型推导（v83 惯例：id / 1000000）。
 */
class ItemConstantsTest {

    @Test
    @DisplayName("装备前缀 → EQUIP")
    void equipIdMapsToEquip() {
        assertThat(ItemConstants.getInventoryType(1302000)).isEqualTo(InventoryType.EQUIP);
        assertThat(ItemConstants.isEquip(1302000)).isTrue();
    }

    @Test
    @DisplayName("消耗前缀 → USE")
    void useIdMapsToUse() {
        assertThat(ItemConstants.getInventoryType(2000000)).isEqualTo(InventoryType.USE);
        assertThat(ItemConstants.isConsume(2000000)).isTrue();
    }

    @Test
    @DisplayName("布置/其他/现金前缀 → SETUP / ETC / CASH")
    void setupEtcCashPrefixes() {
        assertThat(ItemConstants.getInventoryType(3010000)).isEqualTo(InventoryType.SETUP);
        assertThat(ItemConstants.getInventoryType(4000000)).isEqualTo(InventoryType.ETC);
        assertThat(ItemConstants.getInventoryType(5000000)).isEqualTo(InventoryType.CASH);
        assertThat(ItemConstants.isCash(5000000)).isTrue();
    }

    @Test
    @DisplayName("越界前缀 → UNDEFINED")
    void outOfRangeMapsToUndefined() {
        assertThat(ItemConstants.getInventoryType(999999)).isEqualTo(InventoryType.UNDEFINED);
        assertThat(ItemConstants.getInventoryType(6000000)).isEqualTo(InventoryType.UNDEFINED);
        assertThat(ItemConstants.getInventoryType(-1)).isEqualTo(InventoryType.UNDEFINED);
    }
}
