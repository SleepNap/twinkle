package org.gms.data.repo;

import org.gms.data.entity.InventoryItemEntity;

import java.util.List;

/**
 * 背包物品仓库（架构 M3-5 存档：进图回填 + 下线落库）。
 */
public interface InventoryItemRepository {

    /** 某角色的全部物品（回填内存态背包用）。 */
    List<InventoryItemEntity> findByCharacterId(long characterId);

    /** 插入单条物品行（建角默认装备用）。 */
    void insert(InventoryItemEntity item);

    /** 覆盖落库：先删该角色旧行再插入当前快照（简单可靠，角色数量级小）。 */
    void replaceAll(long characterId, List<InventoryItemEntity> items);
}
