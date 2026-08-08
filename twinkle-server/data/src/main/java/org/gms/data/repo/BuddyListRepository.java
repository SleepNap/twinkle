package org.gms.data.repo;

import org.gms.data.entity.BuddyListEntity;

import java.util.List;

/**
 * 好友列表仓库（架构 4.4 单一属主：好友关系真值持久化）。
 */
public interface BuddyListRepository {

    /** 某玩家全部好友（ACCEPTED + PENDING）。 */
    List<BuddyListEntity> findByOwner(long ownerId);

    /** 好友关系是否存在（任意方向，幂等判断）。 */
    boolean exists(long ownerId, long buddyId);

    /** 插入好友关系（INSERT OR IGNORE 语义：重复不报错，返回是否新建）。 */
    boolean insertIfAbsent(BuddyListEntity buddy);

    /** 更新关系状态（PENDING → ACCEPTED / 删除）。 */
    void updateStatus(long ownerId, long buddyId, String status);

    /** 删除好友关系（双向清理由调用方执行两次）。 */
    void delete(long ownerId, long buddyId);
}
