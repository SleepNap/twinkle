package org.gms.channel;

import org.gms.persistence.entity.BuddyListEntity;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.persistence.repo.BuddyListRepository;
import org.gms.persistence.repo.PlayerCharacterRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 好友回归共用的内存持久化桩，避免每个用例重复实现仓库接口。 */
public final class BuddyFixtures {
    private BuddyFixtures() {
    }

    public static BuddyListEntity row(long owner, long buddy, String status) {
        BuddyListEntity entity = new BuddyListEntity();
        entity.setOwnerId(owner);
        entity.setBuddyId(buddy);
        entity.setStatus(status);
        return entity;
    }

    public static PlayerCharacterRecord character(long id, String name) {
        PlayerCharacterRecord record = new PlayerCharacterRecord();
        record.setId(id);
        record.setName(name);
        return record;
    }

    /** 内存好友关系表：按 owner 分桶，并支持按 buddy 反向查询。 */
    public static final class Repository implements BuddyListRepository {
        public final Map<Long, List<BuddyListEntity>> rows = new HashMap<>();

        @Override public List<BuddyListEntity> findByOwner(long ownerId) {
            return new ArrayList<>(rows.getOrDefault(ownerId, List.of()));
        }

        @Override public List<BuddyListEntity> findByBuddy(long buddyId) {
            List<BuddyListEntity> result = new ArrayList<>();
            for (List<BuddyListEntity> owned : rows.values()) {
                for (BuddyListEntity row : owned) {
                    if (row.getBuddyId() == buddyId) result.add(row);
                }
            }
            return result;
        }

        @Override public boolean exists(long ownerId, long buddyId) {
            return findByOwner(ownerId).stream().anyMatch(row -> row.getBuddyId() == buddyId);
        }

        @Override public boolean insertIfAbsent(BuddyListEntity buddy) {
            if (exists(buddy.getOwnerId(), buddy.getBuddyId())) return false;
            rows.computeIfAbsent(buddy.getOwnerId(), ignored -> new ArrayList<>()).add(buddy);
            return true;
        }

        @Override public void updateStatus(long ownerId, long buddyId, String status) {
            for (BuddyListEntity row : rows.getOrDefault(ownerId, List.of())) {
                if (row.getBuddyId() == buddyId) row.setStatus(status);
            }
        }

        @Override public void delete(long ownerId, long buddyId) {
            rows.getOrDefault(ownerId, new ArrayList<>()).removeIf(row -> row.getBuddyId() == buddyId);
        }
    }

    /** 内存角色表：只实现好友链路需要的最小查询。 */
    public static final class Characters implements PlayerCharacterRepository {
        public final Map<Long, PlayerCharacterRecord> records = new HashMap<>();

        @Override public List<PlayerCharacterRecord> findByAccount(int accountId, int world) {
            return List.of();
        }

        @Override public Optional<PlayerCharacterRecord> findById(long id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override public Optional<PlayerCharacterRecord> findByName(String name) {
            return records.values().stream().filter(record -> record.getName().equals(name)).findFirst();
        }

        @Override public boolean existsByName(String name) {
            return findByName(name).isPresent();
        }

        @Override public void insert(PlayerCharacterRecord chr) {
        }

        @Override public void save(PlayerCharacterRecord chr) {
        }
    }
}
