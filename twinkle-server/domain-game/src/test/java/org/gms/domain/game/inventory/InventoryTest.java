package org.gms.domain.game.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 背包数据结构：自动分配槽位、满判定、移除。
 */
class InventoryTest {

    private final Inventory inv = new Inventory(InventoryType.USE, 3);

    @Test
    @DisplayName("addItem 自动分配槽位并回写 position")
    void addItemAssignsNextFreeSlot() {
        Item a = new Item(2000000);
        Item b = new Item(2000001);

        assertThat(inv.addItem(a)).isTrue();
        assertThat(inv.addItem(b)).isTrue();

        assertThat(a.getPosition()).isEqualTo((short) 1);
        assertThat(b.getPosition()).isEqualTo((short) 2);
        assertThat(inv.getItem((short) 1)).isSameAs(a);
        assertThat(inv.getItem((short) 2)).isSameAs(b);
        assertThat(inv.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("背包满时 addItem 返回 false，不占用多余槽位")
    void addItemWhenFullReturnsFalse() {
        assertThat(inv.addItem(new Item(2000000))).isTrue();
        assertThat(inv.addItem(new Item(2000001))).isTrue();
        assertThat(inv.addItem(new Item(2000002))).isTrue();
        assertThat(inv.isFull()).isTrue();

        assertThat(inv.addItem(new Item(2000003))).isFalse();
        assertThat(inv.size()).isEqualTo(3);
        assertThat(inv.getNextFreeSlot()).isEqualTo((short) -1);
    }

    @Test
    @DisplayName("移除槽位后槽位可复用")
    void removeItemFreesSlot() {
        Item a = new Item(2000000);
        inv.addItem(a);
        inv.removeItem((short) 1);

        assertThat(inv.getItem((short) 1)).isNull();
        assertThat(inv.getNextFreeSlot()).isEqualTo((short) 1);
        assertThat(inv.size()).isEqualTo(0);
    }

    @Test
    @DisplayName("items() 是不可变视图")
    void itemsViewIsImmutable() {
        inv.addItem(new Item(2000000));

        assertThat(inv.items()).containsExactly(inv.getItem((short) 1));
    }
}
