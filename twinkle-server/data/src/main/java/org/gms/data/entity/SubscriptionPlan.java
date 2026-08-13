package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 订阅计划（V12 迁移建表）。月/周/5h 三档滚动限额；限额为 0 表示该窗口不限制。
 */
@Table("subscription_plan")
@Getter
@Setter
public class SubscriptionPlan {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String planCode;
    private String displayName;
    private Long monthlyLimit;
    private Long weeklyLimit;
    private Long fiveHourLimit;
    private Integer priceNx;
    private Integer enabled;
}
