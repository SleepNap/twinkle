package org.gms.data.repo;

import com.mybatisflex.core.query.QueryWrapper;
import org.gms.data.entity.BuddyListEntity;
import org.gms.data.mapper.BuddyListMapper;

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
        buddy.setCreatedAt(Instant.now().toString());
        mapper.insert(buddy);
        return true;
    }

    @Override
    public void updateStatus(long ownerId, long buddyId, String status) {
        BuddyListEntity e = new BuddyListEntity();
        e.setStatus(status);
        mapper.updateByQuery(e, QueryWrapper.create()
                .where(BuddyListEntity::getOwnerId).eq(ownerId)
                .and(BuddyListEntity::getBuddyId).eq(buddyId));
    }

    @Override
    public void delete(long ownerId, long buddyId) {
        mapper.deleteByQuery(QueryWrapper.create()
                .where(BuddyListEntity::getOwnerId).eq(ownerId)
                .and(BuddyListEntity::getBuddyId).eq(buddyId));
    }
}
