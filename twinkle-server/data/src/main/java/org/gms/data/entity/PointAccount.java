package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 积分账户（账号维度，V12 迁移建表）。同一账号所有 API Key 共享积分余额与 plan 限额。
 */
@Table("point_account")
@Getter
@Setter
public class PointAccount {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long accountId;
    private Long balance;
    private Long planId;
    private Long monthlyUsed;
    private Long weeklyUsed;
    private Long fiveHourUsed;
    private String monthlyWindowStart;
    private String weeklyWindowStart;
    private String fiveHourWindowStart;
    private String createdAt;
    private String updatedAt;
}
