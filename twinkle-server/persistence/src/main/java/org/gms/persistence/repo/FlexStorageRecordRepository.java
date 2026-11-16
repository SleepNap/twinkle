package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.gms.persistence.entity.StorageRecordEntity;
import org.gms.persistence.mapper.StorageRecordMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis-Flex 实现的账号仓库头仓库（storage/trunk 持久化）。
 *
 * <p>装配由 {@code MyBatisFlexFactory} 统一负责（@Bean），此处不用 @Singleton 自注册。
 *
 * <p>查询一律分语句构造：{@code QueryWrapper.create().where(...).eq(...).orderBy(...)}
 * 返回的是 {@code QueryOrderByBuilder}，不能直接交给 {@code selectListByQuery}。
 */
public class FlexStorageRecordRepository implements StorageRecordRepository {

    private final StorageRecordMapper mapper;

    public FlexStorageRecordRepository(StorageRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<StorageRecordEntity> findByAccount(int accountId, int world) {
        QueryWrapper query = QueryWrapper.create();
        query.where(StorageRecordEntity::getAccountId).eq(accountId);
        query.and(StorageRecordEntity::getWorld).eq(world);
        List<StorageRecordEntity> rows = mapper.selectListByQuery(query);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public StorageRecordEntity createIfAbsent(int accountId, int world, int slotCount) {
        // 先查再插：并发下两个请求可能同时判定不存在，重复插入由唯一索引拦下，
        // 调用方据乐观锁/唯一约束重读即可（不做 upsert，避免方言差异）。
        Optional<StorageRecordEntity> existing = findByAccount(accountId, world);
        if (existing.isPresent()) {
            return existing.get();
        }
        StorageRecordEntity record = new StorageRecordEntity();
        record.setAccountId(accountId);
        record.setWorld(world);
        record.setSlotCount(slotCount);
        record.setMeso(0);
        record.setRowVersion(0);
        record.setUpdatedAt(Instant.now().toString());
        mapper.insert(record);
        return record;
    }

    @Override
    public boolean updateMesoAndSlots(long storageRecordId, int meso, int slotCount, int expectedVersion) {
        // 只写这几列：updateByQuery(entity, qw) 会把实体里非 null 的原始类型字段一起写回（历史事故）。
        return UpdateChain.of(mapper)
                .where(StorageRecordEntity::getStorageRecordId).eq(storageRecordId)
                .and(StorageRecordEntity::getRowVersion).eq(expectedVersion)
                .set(StorageRecordEntity::getMeso, meso)
                .set(StorageRecordEntity::getSlotCount, slotCount)
                .set(StorageRecordEntity::getRowVersion, expectedVersion + 1)
                .set(StorageRecordEntity::getUpdatedAt, Instant.now().toString())
                .update();
    }

    @Override
    public void deleteByAccount(int accountId) {
        QueryWrapper query = QueryWrapper.create();
        query.where(StorageRecordEntity::getAccountId).eq(accountId);
        mapper.deleteByQuery(query);
    }
}
