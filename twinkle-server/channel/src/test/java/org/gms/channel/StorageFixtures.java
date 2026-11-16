package org.gms.channel;

import org.gms.concurrent.SerialTaskQueue;
import org.gms.persistence.entity.StorageItemEntity;
import org.gms.persistence.entity.StorageRecordEntity;
import org.gms.persistence.repo.StorageItemRepository;
import org.gms.persistence.repo.StorageRecordRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 账号仓库（storage/trunk）回归共用的内存持久化桩与同步 IO 队列。
 *
 * <p>三个刻意选择，改测试前先看：
 *
 * <ul>
 *   <li><b>不能给 {@code StorageService} 传 {@code io == null}</b>：{@code offload} 在 null 时
 *       同步执行，但落库入口 {@code persist} 没有同样的兜底，直接调用 {@code io.submit(...)}；
 *       传 null 会在「存入/取出/金币搬运」的第一条变更上抛 NPE。故这里用「直接执行」的
 *       {@link SerialTaskQueue}：任务在调用线程内联跑完，既有同步语义，也不创建线程。</li>
 *   <li><b>读库返回逐字段副本</b>：{@link StorageService} 加载后会对实体重排 {@code slotIndex}，
 *       共享同一实例会让「频道内存态」与「已落库快照」混为一谈，断言就测不到真实落库结果。</li>
 *   <li><b>仓库头按 accountId 分桶</b>：一个 fake 实例只服务一个世界（{@link #WORLD}），
 *       与 {@code storage_records} 的 {@code (account_id, world)} 唯一键等价。</li>
 * </ul>
 *
 * <p>服务端「内存态是权威、落库是覆盖写」的语义在这里被完整保留：{@code replaceAll} 覆盖整份
 * 物品行，{@code updateMesoAndSlots} 走 {@code row_version} 乐观锁（匹配才 +1）。
 */
public final class StorageFixtures {

    /** 测试世界 id：仓库头按 (accountId, world) 唯一，与服务端装配一致。 */
    public static final int WORLD = 1;

    private StorageFixtures() {
    }

    /**
     * 同步 IO 队列：用直接执行器把 {@code submit} 的任务在调用线程内跑完。
     *
     * <p>只是为了让 {@code persist} 拿到非 null 队列而不引入线程；容量给足，避免任务被拒。
     */
    public static SerialTaskQueue inlineIo() {
        return new SerialTaskQueue(Runnable::run, 1024);
    }

    /** 预置一条仓库物品行（跳过真实存入流程，用于构造「仓库已有某件物品」的起始状态）。 */
    public static StorageItemEntity item(long recordId, int accountId, int inventoryType, int slotIndex,
                                        int itemId, int quantity) {
        StorageItemEntity row = new StorageItemEntity();
        row.setStorageRecordId(recordId);
        row.setAccountId(accountId);
        row.setInventoryType(inventoryType);
        row.setSlotIndex(slotIndex);
        row.setItemId(itemId);
        row.setQuantity(quantity);
        return row;
    }

    /** 逐字段复制：读库与断言都走副本，避免服务端就地改 {@code slotIndex} 影响 fake 里的行。 */
    public static StorageItemEntity copyOf(StorageItemEntity source) {
        StorageItemEntity copy = new StorageItemEntity();
        copy.setStorageItemId(source.getStorageItemId());
        copy.setStorageRecordId(source.getStorageRecordId());
        copy.setAccountId(source.getAccountId());
        copy.setInventoryType(source.getInventoryType());
        copy.setSlotIndex(source.getSlotIndex());
        copy.setItemId(source.getItemId());
        copy.setQuantity(source.getQuantity());
        copy.setOwner(source.getOwner());
        copy.setPetId(source.getPetId());
        copy.setFlag(source.getFlag());
        copy.setExpiration(source.getExpiration());
        copy.setGiftFrom(source.getGiftFrom());
        copy.setCashId(source.getCashId());
        copy.setUpgradeSlots(source.getUpgradeSlots());
        copy.setLevel(source.getLevel());
        copy.setStrStat(source.getStrStat());
        copy.setDexStat(source.getDexStat());
        copy.setIntStat(source.getIntStat());
        copy.setLukStat(source.getLukStat());
        copy.setHp(source.getHp());
        copy.setMp(source.getMp());
        copy.setWAtk(source.getWAtk());
        copy.setMAtk(source.getMAtk());
        copy.setWDef(source.getWDef());
        copy.setMDef(source.getMDef());
        copy.setAcc(source.getAcc());
        copy.setAvoid(source.getAvoid());
        copy.setHands(source.getHands());
        copy.setSpeed(source.getSpeed());
        copy.setJump(source.getJump());
        copy.setVicious(source.getVicious());
        copy.setItemLevel(source.getItemLevel());
        copy.setItemExp(source.getItemExp());
        copy.setRingId(source.getRingId());
        copy.setPetName(source.getPetName());
        copy.setPetLevel(source.getPetLevel());
        copy.setPetCloseness(source.getPetCloseness());
        copy.setPetFullness(source.getPetFullness());
        copy.setPetAttribute(source.getPetAttribute());
        copy.setPetSkill(source.getPetSkill());
        copy.setPetRemainLife(source.getPetRemainLife());
        copy.setItemAttribute(source.getItemAttribute());
        return copy;
    }

    /** 内存仓库头表：{@code createIfAbsent} 回填自增 id，{@code updateMesoAndSlots} 走版本门。 */
    public static final class Records implements StorageRecordRepository {
        public final Map<Integer, StorageRecordEntity> rows = new HashMap<>();
        private long nextId = 1L;

        /** 预置仓库头（用例需要特定 {@code slotCount} 时用），返回可继续断言的同一实例。 */
        public StorageRecordEntity seed(int accountId, int world, int slotCount) {
            StorageRecordEntity row = new StorageRecordEntity();
            row.setStorageRecordId(nextId++);
            row.setAccountId(accountId);
            row.setWorld(world);
            row.setSlotCount(slotCount);
            row.setMeso(0);
            row.setRowVersion(0);
            row.setUpdatedAt("1970-01-01T00:00:00Z");
            rows.put(accountId, row);
            return row;
        }

        /** 断言用取行：不存在说明前置步骤没建行，直接暴露成失败而不是后续 NPE。 */
        public StorageRecordEntity byAccount(int accountId) {
            StorageRecordEntity row = rows.get(accountId);
            if (row == null) {
                throw new AssertionError("仓库头不存在，accountId=" + accountId);
            }
            return row;
        }

        @Override public Optional<StorageRecordEntity> findByAccount(int accountId, int world) {
            StorageRecordEntity row = rows.get(accountId);
            return row != null && row.getWorld() == world ? Optional.of(row) : Optional.empty();
        }

        @Override public StorageRecordEntity createIfAbsent(int accountId, int world, int slotCount) {
            StorageRecordEntity existing = rows.get(accountId);
            return existing != null ? existing : seed(accountId, world, slotCount);
        }

        @Override public boolean updateMesoAndSlots(long storageRecordId, int meso, int slotCount,
                                                    int expectedVersion) {
            for (StorageRecordEntity row : rows.values()) {
                Long id = row.getStorageRecordId();
                if (id == null || id != storageRecordId) {
                    continue;
                }
                if (row.getRowVersion() != expectedVersion) {
                    return false;
                }
                row.setMeso(meso);
                row.setSlotCount(slotCount);
                row.setRowVersion(expectedVersion + 1);
                return true;
            }
            return false;
        }

        @Override public void deleteByAccount(int accountId) {
            rows.remove(accountId);
        }
    }

    /** 内存仓库物品表：按 {@code storageRecordId} 分桶，读出时按 (类型, 下标) 升序。 */
    public static final class Items implements StorageItemRepository {
        public final Map<Long, List<StorageItemEntity>> rows = new HashMap<>();

        /** 预置一行库存（跳过真实存入流程），入表即副本。 */
        public StorageItemEntity seed(StorageItemEntity row) {
            rows.computeIfAbsent(row.getStorageRecordId(), ignored -> new ArrayList<>()).add(copyOf(row));
            return row;
        }

        /** 某个仓库下已落库的物品行（副本、按 (类型, 下标) 升序），测试断言用。 */
        public List<StorageItemEntity> stored(long storageRecordId) {
            List<StorageItemEntity> result = new ArrayList<>();
            for (StorageItemEntity row : rows.getOrDefault(storageRecordId, List.of())) {
                result.add(copyOf(row));
            }
            result.sort(Comparator.comparingInt(StorageItemEntity::getInventoryType)
                    .thenComparingInt(StorageItemEntity::getSlotIndex));
            return result;
        }

        /** 该账号全部已落库物品行（副本、按 (类型, 下标) 升序）；不知道仓库头 id 时用。 */
        public List<StorageItemEntity> storedByAccount(int accountId) {
            List<StorageItemEntity> result = new ArrayList<>();
            for (List<StorageItemEntity> bucket : rows.values()) {
                for (StorageItemEntity row : bucket) {
                    if (row.getAccountId() == accountId) {
                        result.add(copyOf(row));
                    }
                }
            }
            result.sort(Comparator.comparingInt(StorageItemEntity::getInventoryType)
                    .thenComparingInt(StorageItemEntity::getSlotIndex));
            return result;
        }

        @Override public List<StorageItemEntity> findByRecord(long storageRecordId) {
            return stored(storageRecordId);
        }

        @Override public void replaceAll(long storageRecordId, int accountId, List<StorageItemEntity> items) {
            List<StorageItemEntity> copies = new ArrayList<>();
            if (items != null) {
                for (StorageItemEntity row : items) {
                    copies.add(copyOf(row));
                }
            }
            rows.put(storageRecordId, copies);
        }

        @Override public void deleteByAccount(int accountId) {
            for (List<StorageItemEntity> bucket : rows.values()) {
                bucket.removeIf(row -> row.getAccountId() == accountId);
            }
        }
    }
}
