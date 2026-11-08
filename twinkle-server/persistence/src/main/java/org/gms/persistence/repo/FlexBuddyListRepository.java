package org.gms.persistence.repo;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateChain;
import org.gms.persistence.entity.BuddyListEntity;
import org.gms.persistence.mapper.BuddyListMapper;

import java.time.Instant;
import java.util.List;

/**
 * MyBatis-Flex 实现的好友列表仓库（M4 单一属主持久化）。
 */
public class FlexBuddyListRepository implements BuddyListRepository {

    private final BuddyListMapper mapper;

    public FlexBuddyListRepository(BuddyListMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<BuddyListEntity> findByOwner(long ownerId) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(BuddyListEntity::getOwnerId).eq(ownerId));
    }

    @Override
    public List<BuddyListEntity> findByBuddy(long buddyId) {
        return mapper.selectListByQuery(QueryWrapper.create()
                .where(BuddyListEntity::getBuddyId).eq(buddyId));
    }

    @Override
    public boolean exists(long ownerId, long buddyId) {
        return mapper.selectCountByQuery(QueryWrapper.create()
                .where(BuddyListEntity::getOwnerId).eq(ownerId)
                .and(BuddyListEntity::getBuddyId).eq(buddyId)) > 0;
    }

    @Override
    public boolean insertIfAbsent(BuddyListEntity buddy) {
        if (exists(buddy.getOwnerId(), buddy.getBuddyId())) {
            return false;
        }
        if (buddy.getGroupName() == null || buddy.getGroupName().isBlank()) {
            buddy.setGroupName(BuddyListEntity.DEFAULT_GROUP);
        }
        buddy.setCreatedAt(Instant.now().toString());
        mapper.insert(buddy);
        return true;
    }

    @Override
    public void updateStatus(long ownerId, long buddyId, String status) {
        // 复合主键没有 @Id 可跳过：用 UpdateChain 只写 status，避免实体的 long 默认值把
        // owner_id/buddy_id 一起改成 0（updateByQuery 会把非 null 的原始类型字段也写回）。
        UpdateChain.of(mapper)
                .where(BuddyListEntity::getOwnerId).eq(ownerId)
                .and(BuddyListEntity::getBuddyId).eq(buddyId)
                .set(BuddyListEntity::getStatus, status)
                .update();
    }

    @Override
    public void delete(long ownerId, long buddyId) {
        mapper.deleteByQuery(QueryWrapper.create()
                .where(BuddyListEntity::getOwnerId).eq(ownerId)
                .and(BuddyListEntity::getBuddyId).eq(buddyId));
    }
}
