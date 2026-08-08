package org.gms.data.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 好友列表表实体（架构 4.4 单一属主：好友关系真值持久化，V6 迁移建表）。
 *
 * <p>复合主键（owner_id + buddy_id），MyBatis-Flex 不支持复合主键自动映射，本实体以
 * owner_id/buddy_id 作为查询键（MyBatis-Flex 允许无 @Id 的普通表，增删改用 QueryWrapper）。
 * status：PENDING（待确认）/ ACCEPTED（已确认）。
 */
@Table("buddylist")
@Getter
@Setter
public class BuddyListEntity {

    @Column("owner_id")
    private long ownerId;
    @Column("buddy_id")
    private long buddyId;
    private String status;
    private String createdAt;

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
}
