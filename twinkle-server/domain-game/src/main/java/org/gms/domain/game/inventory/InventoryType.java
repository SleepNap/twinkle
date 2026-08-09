package org.gms.domain.game.inventory;

/**
 * 背包类型（v83 协议字节值，红线 1：字节级兼容）。
 *
 * <p>物品 id 高 7 位即类型（{@link ItemConstants#getInventoryType(int)}）。
 * byte 值在 v83 封包里直接使用，不可改。
 */
public enum InventoryType {

    /** 未定义 / 无法归类。 */
    UNDEFINED((byte) 0),
    /** 装备（Equip）。 */
    EQUIP((byte) 1),
    /** 消耗（use，药水/卷轴等）。 */
    USE((byte) 2),
    /** 布置（setup，椅子/箭筒等）。 */
    SETUP((byte) 3),
    /** 其他（etc，任务道具等）。 */
    ETC((byte) 4),
    /** 现金（cash，点券道具）。 */
    CASH((byte) 5);

    private final byte type;

    private InventoryType(byte type) {
        this.type = type;
    }

    public byte getType() {
        return type;
    }

    /** 按 v83 字节值取类型，未知值回 {@link #UNDEFINED}。 */
    public static InventoryType getByType(byte type) {
        for (InventoryType t : values()) {
            if (t.type == type) {
                return t;
            }
        }
        return UNDEFINED;
    }
}
