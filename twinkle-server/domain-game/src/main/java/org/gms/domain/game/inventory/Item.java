package org.gms.domain.game.inventory;

import org.gms.i18n.I18n;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.gms.concurrent.GameExecution;

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
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private GameExecution execution;

    public final void bindExecution(GameExecution owner) {
        owner.requireOwner();
        if (execution != null && execution != owner) throw new IllegalStateException(I18n.message("error.execution.item_owner"));
        execution = owner;
    }
    protected final void requireStateAccess() { if (execution != null) execution.requireOwner(); }

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
    /** 过期时间（Unix 毫秒时间戳；-1 表示永不过期，0 不具有永久语义）。 */
    private long expiration = -1;
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

    // 受控写入口：Lombok 的普通 setter 无法校验频道执行归属。
    public void setCashId(int value) { requireStateAccess(); this.cashId = value; }
    public void setPosition(short value) { requireStateAccess(); this.position = value; }
    public void setQuantity(short value) { requireStateAccess(); this.quantity = value; }
    public void setPetId(int value) { requireStateAccess(); this.petId = value; }
    public void setOwner(String value) { requireStateAccess(); this.owner = value; }
    public void setFlag(int value) { requireStateAccess(); this.flag = value; }
    public void setExpiration(long value) { requireStateAccess(); this.expiration = value; }
    public void setGiftFrom(String value) { requireStateAccess(); this.giftFrom = value; }
}
