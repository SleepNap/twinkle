package org.gms.domain.game;

import org.gms.domain.game.inventory.Inventory;
import org.gms.domain.game.inventory.InventoryType;

import java.util.EnumMap;

/** 玩家背包子聚合；隔离背包容器的创建和持有。 */
public final class CharacterInventory {
    private final EnumMap<InventoryType, Inventory> inventories = new EnumMap<>(InventoryType.class);

    public Inventory getOrCreate(InventoryType type, int slotLimit) {
        return inventories.computeIfAbsent(type, ignored -> new Inventory(type, slotLimit));
    }

    EnumMap<InventoryType, Inventory> mutableInventories() {
        return inventories;
    }
}
