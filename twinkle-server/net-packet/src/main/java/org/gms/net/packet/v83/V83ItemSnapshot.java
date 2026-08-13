package org.gms.net.packet.v83;

import org.gms.i18n.I18n;

/**
 * v83 物品发包投影，隔离协议层与游戏对象/数据库实体。
 *
 * @param itemType 1=装备、2=普通/宠物物品
 * @param cashId 现金物品唯一 id；宠物使用 petId，0 表示普通物品
 */
public record V83ItemSnapshot(
        int position,
        int itemType,
        int itemId,
        long cashId,
        long expiration,
        int quantity,
        String owner,
        int flag,
        V83EquipStats equipStats,
        V83PetStats petStats) {

    public V83ItemSnapshot(int position, int itemType, int itemId, long cashId, long expiration,
                           int quantity, String owner, int flag, V83EquipStats equipStats) {
        this(position, itemType, itemId, cashId, expiration, quantity, owner, flag, equipStats, null);
    }

    public V83ItemSnapshot {
        owner = owner == null ? "" : owner;
        if (itemType != 1 && itemType != 2) {
            throw new IllegalArgumentException(I18n.message("error.item.unsupported_type", itemType));
        }
        if (itemType == 1 && equipStats == null) {
            equipStats = V83EquipStats.empty();
        }
        if (petStats != null && (itemType != 2 || cashId <= 0)) {
            throw new IllegalArgumentException(I18n.message("error.item.pet_projection_invalid"));
        }
    }

    public boolean cash() {
        return cashId > 0;
    }
}
