package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * AI 权限与预算策略（账号维度，V20 迁移建表）。
 *
 * <p>挂 {@code account_records.id}，与 {@link PointAccount} 同口径：同一账号的所有 API Key
 * 共享一份 AI 预算与模型白名单。不挂 subject_id——所有 key 的 subject_id 都继承签发者，
 * 控制台签发出来恒为 {@code subject_owner}，挂上去会塌缩成"全体一份"。
 *
 * <p>限额语义沿用 {@link SubscriptionPlan}：{@code >0} 生效，{@code 0} = 不限制。
 * 账号没有策略行时视为不限制（只受全局开关约束）。
 */
@Table("ai_usage_policy")
@Getter
@Setter
public class AiUsagePolicy {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long accountId;
    /** 是否允许该账号调用 AI；0 表示整体禁用。 */
    private Integer enabled;
    /** 允许的模型 descriptor 列表（逗号分隔，provider/modelName）；留空表示不限制。 */
    private String allowedModels;
    private Long dailyPointLimit;
    private Long dailyCallLimit;
    private Long dailyTokenLimit;
    private Long dailyPointUsed;
    private Long dailyCallUsed;
    private Long dailyTokenUsed;
    /** 当前 24h 滚动窗口起点（ISO-8601）。 */
    private String windowStart;
    private String createdAt;
    private String updatedAt;
    private String updatedBy;
}
