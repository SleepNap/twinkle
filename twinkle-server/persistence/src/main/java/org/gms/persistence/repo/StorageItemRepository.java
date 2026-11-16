package org.gms.persistence.repo;

import org.gms.persistence.entity.StorageItemEntity;

import java.util.List;

/**
 * 账号仓库物品行仓库（storage/trunk 持久化：开仓库回填 + 变更后覆盖落库）。
 */
public interface StorageItemRepository {

    /** 某仓库的全部物品，按 (inventory_type, slot_index) 升序。 */
    List<StorageItemEntity> findByRecord(long storageRecordId);

    /** 覆盖落库：先删该仓库旧行，再插入当前快照。 */
    void replaceAll(long storageRecordId, int accountId, List<StorageItemEntity> items);

    /** 账号删除清理。 */
    void deleteByAccount(int accountId);
}
