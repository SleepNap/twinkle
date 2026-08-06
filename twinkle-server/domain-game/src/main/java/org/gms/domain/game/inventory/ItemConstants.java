package org.gms.domain.game.inventory;

/**
 * 物品常量与推导（稳定层核心机制，思路参考自 BeiDou-Server 的 ItemConstants）。
 *
 * <p>v83 物品 id 高 7 位即背包类型：{@code itemId / 1000000} ∈ [1,5] 对应
 * EQUIP / USE / SETUP / ETC / CASH。这是协议与逻辑共用的分类规则。
 */
public final class ItemConstants {

    private ItemConstants() {
    }

    /** 物品 id → 背包类型；超出 [1,5] 前缀回 {@link InventoryType#UNDEFINED}。 */
    public static InventoryType getInventoryType(int itemId) {
        byte type = (byte) (itemId / 1000000);
        if (type < 1 || type > 5) {
            return InventoryType.UNDEFINED;
        }
        return InventoryType.getByType(type);
    }

    public static boolean isEquip(int itemId) {
        return getInventoryType(itemId) == InventoryType.EQUIP;
    }

    public static boolean isConsume(int itemId) {
        return getInventoryType(itemId) == InventoryType.USE;
    }

    public static boolean isCash(int itemId) {
        return getInventoryType(itemId) == InventoryType.CASH;
    }
}
