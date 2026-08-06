package org.gms.domain.game.inventory;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * 物品（纯数据，稳定层）。字段集对齐 v83 存档 inventory 结构（红线 3，思路参考自 BeiDou-Server）。
 * Lombok 生成 getter/setter（红线 11），id 不可变（final）。
 *
 * <p>游戏对象手动 new、不进容器（红线 4）。{@link #copy()} 深拷贝——交易/商店等
 * 需要独立实例的场景用它，防止引用共享导致改一处动两处。
 */
@Getter
@Setter
public class Item {

    @Setter(AccessLevel.NONE)
    private final int id;
    /** 唯一实例 id（现金道具/重复装备用；0 表示非实例）。 */
    private int cashId;
    /** 背包槽位（1 起）。 */
    private short position;
    /** 数量（非装备通常 &gt; 1）。 */
    private short quantity;
    private int petId;
    private String owner;
    private int flag;
    /** 过期时间（0 = 永不过期）。 */
    private long expiration;
    private String giftFrom;

    public Item(int id) {
        this.id = id;
        this.quantity = 1;
    }

    /** 基础字段复制到 target（供 {@link Equip#copy()} 复用）。 */
    protected final void copyBase(Item target) {
        target.cashId = cashId;
        target.position = position;
        target.quantity = quantity;
        target.petId = petId;
        target.owner = owner;
        target.flag = flag;
        target.expiration = expiration;
        target.giftFrom = giftFrom;
    }

    /** 深拷贝：独立实例，改动互不影响。 */
    public Item copy() {
        Item copy = new Item(id);
        copyBase(copy);
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Item item)) {
            return false;
        }
        return id == item.id
                && cashId == item.cashId
                && position == item.position
                && quantity == item.quantity
                && petId == item.petId
                && flag == item.flag
                && expiration == item.expiration;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, cashId, position, quantity, petId, flag, expiration);
    }
}
