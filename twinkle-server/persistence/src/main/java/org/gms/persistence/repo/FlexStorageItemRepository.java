package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.persistence.entity.StorageItemEntity;
import org.gms.persistence.mapper.StorageItemMapper;

import java.util.List;

/**
 * MyBatis-Flex 实现的账号仓库物品行仓库（storage/trunk 持久化）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不用 @Singleton 自注册。
 */
public class FlexStorageItemRepository implements StorageItemRepository {

    private final StorageItemMapper mapper;

    public FlexStorageItemRepository(StorageItemMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<StorageItemEntity> findByRecord(long storageRecordId) {
        // 分语句构造：where(...).eq(...).orderBy(...) 返回 QueryOrderByBuilder，不能直接传 selectListByQuery。
        // orderBy 必须用 (lambda, asc) 两参形式：单参 orderBy(lambda) 在 1.11.8 不产生 ORDER BY（实测空转）。
        QueryWrapper query = QueryWrapper.create();
        query.where(StorageItemEntity::getStorageRecordId).eq(storageRecordId);
        query.orderBy(StorageItemEntity::getInventoryType, true);
        query.orderBy(StorageItemEntity::getSlotIndex, true);
        return mapper.selectListByQuery(query);
    }

    @Override
    public void replaceAll(long storageRecordId, int accountId, List<StorageItemEntity> items) {
        QueryWrapper query = QueryWrapper.create();
        query.where(StorageItemEntity::getStorageRecordId).eq(storageRecordId);
        mapper.deleteByQuery(query);
        for (StorageItemEntity item : items) {
            // 快照复用旧实例时主键可能是上一次落库的自增值，清掉走自增，
            // 否则会与"先删后插"后残留的其他仓库行撞主键。
            item.setStorageItemId(null);
            item.setStorageRecordId(storageRecordId);
            item.setAccountId(accountId);
            mapper.insert(item);
        }
    }

    @Override
    public void deleteByAccount(int accountId) {
        QueryWrapper query = QueryWrapper.create();
        query.where(StorageItemEntity::getAccountId).eq(accountId);
        mapper.deleteByQuery(query);
    }
}
