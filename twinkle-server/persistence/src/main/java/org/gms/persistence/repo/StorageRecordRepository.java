package org.gms.persistence.repo;

import org.gms.persistence.entity.StorageRecordEntity;

import java.util.Optional;

/**
 * 账号仓库头仓库（storage/trunk 持久化：登录/开仓库时读，金币变动与扩槽后写）。
 */
public interface StorageRecordRepository {

    /** 按账号 + 世界读仓库头；不存在返回 empty。 */
    Optional<StorageRecordEntity> findByAccount(int accountId, int world);

    /** 不存在则建（slot_count 用传入值），返回最终行；已存在直接返回原行。 */
    StorageRecordEntity createIfAbsent(int accountId, int world, int slotCount);

    /** 乐观锁更新金币与槽位数：row_version 必须匹配，成功时 row_version+1 并更新 updated_at。 */
    boolean updateMesoAndSlots(long storageRecordId, int meso, int slotCount, int expectedVersion);

    /** 账号删除清理。 */
    void deleteByAccount(int accountId);
}
