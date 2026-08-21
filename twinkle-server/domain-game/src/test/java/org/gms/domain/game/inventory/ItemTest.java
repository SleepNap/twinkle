package org.gms.domain.game.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 物品纯数据与深拷贝：copy 独立实例，改动互不影响；Equip 扩展字段随 copy 携带。
 */
class ItemTest {

    @Test
    @DisplayName("新建物品默认数量 1 且永不过期")
    void newItemDefaultsQuantityToOne() {
        Item item = new Item(2000000);

        assertThat(item.getId()).isEqualTo(2000000);
        assertThat(item.getQuantity()).isEqualTo((short) 1);
        assertThat(item.getPosition()).isEqualTo((short) 0);
        assertThat(item.getExpiration()).isEqualTo(-1);
    }

    @Test
    @DisplayName("copy 是独立实例：改副本不影响原物")
    void copyIsIndependent() {
        Item item = new Item(2000000);
        item.setQuantity((short) 5);
        item.setOwner("tester");
        item.setPosition((short) 3);

        Item copy = item.copy();
        copy.setQuantity((short) 1);
        copy.setOwner("other");

        assertThat(item.getQuantity()).isEqualTo((short) 5);
        assertThat(item.getOwner()).isEqualTo("tester");
        assertThat(item.getPosition()).isEqualTo((short) 3);
        assertThat(copy.getQuantity()).isEqualTo((short) 1);
        assertThat(copy.getId()).isEqualTo(2000000);
    }

    @Test
    @DisplayName("Equip copy 携带装备扩展字段")
    void equipCopyCarriesEquipFields() {
        Equip equip = new Equip(1302000);
        equip.setUpgradeSlots((byte) 5);
        equip.setWAtk((short) 37);
        equip.setStrStat((short) 4);
        equip.setPosition((short) 1);

        Equip copy = equip.copy();
        copy.setWAtk((short) 10);

        assertThat(copy.getUpgradeSlots()).isEqualTo((byte) 5);
        assertThat(copy.getStrStat()).isEqualTo((short) 4);
        assertThat(copy.getId()).isEqualTo(1302000);
        assertThat(equip.getWAtk()).isEqualTo((short) 37); // 原物不受副本修改影响
        assertThat(copy.getWAtk()).isEqualTo((short) 10);
    }

    @Test
    @DisplayName("同一字段值物品 equals 相等")
    void sameValuesAreEqual() {
        Item a = new Item(2000000);
        a.setQuantity((short) 3);
        Item b = new Item(2000000);
        b.setQuantity((short) 3);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }
}
