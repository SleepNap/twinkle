package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 积分流水（V12 迁移建表）。正数为充值/签到/调账，负数为 AI/联网搜索消耗。
 */
@Table("point_transaction")
@Getter
@Setter
public class PointTransaction {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long accountId;
    private Long changeAmount;
    private Long balanceAfter;
    private String reason;
    private String referenceId;
    private String detail;
    private String createdAt;
}
