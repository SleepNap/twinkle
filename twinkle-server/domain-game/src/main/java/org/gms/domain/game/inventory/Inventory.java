package org.gms.domain.game.inventory;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 背包（纯数据结构，稳定层）。槽位 → 物品的容器，v83 槽位 1 起。
 * Lombok 生成 getter/setter（红线 11）；内部 items 容器用自定义方法。
 *
 * <p>这里只做数据结构操作（增/删/查/槽位分配）；"给不给/扣不扣/发不发包"等
 * 业务判定属可替换层逻辑系统（M2-2），不在此类。
 */
@Getter
@Setter
public class Inventory {

    private final InventoryType type;
    private final int slotLimit;

    @Getter(AccessLevel.NONE)
    private final Map<Short, Item> items = new HashMap<>();

    public Inventory(InventoryType type, int slotLimit) {
        this.type = type;
        this.slotLimit = slotLimit;
    }

    public Item getItem(short slot) {
        return items.get(slot);
    }

    /**
     * 放入物品：自动分配第一个空闲槽位并回写 {@link Item#setPosition(short)}。
     *
     * @return 是否放入成功（背包满返回 false）
     */
    public boolean addItem(Item item) {
        short slot = getNextFreeSlot();
        if (slot < 0) {
            return false;
        }
        item.setPosition(slot);
        items.put(slot, item);
        return true;
    }

    /**
     * 按指定槽位放入（已穿戴装备用负槽位：-5 帽 / -6 脸饰 / -7 / -11 武器）。
     * 不回写 position（调用方已设）；同槽位覆盖。加载存档时用。
     */
    public void putAtSlot(short slot, Item item) {
        items.put(slot, item);
    }

    /** 移除指定槽位物品（无论是否存在）。 */
    public void removeItem(short slot) {
        items.remove(slot);
    }

    /** 下一个空闲槽位；满返回 -1。 */
    public short getNextFreeSlot() {
        for (short s = 1; s <= slotLimit; s++) {
            if (!items.containsKey(s)) {
                return s;
            }
        }
        return -1;
    }

    public boolean isFull() {
        return items.size() >= slotLimit;
    }

    /** 空槽数（槽位上限 - 已用）。 */
    public int freeSlots() {
        return Math.max(0, slotLimit - items.size());
    }

    /** 全部物品（不可变视图）。 */
    public Collection<Item> items() {
        return List.copyOf(items.values());
    }

    public int size() {
        return items.size();
    }
}
