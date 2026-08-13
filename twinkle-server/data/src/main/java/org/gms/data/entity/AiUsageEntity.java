package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * AI 使用记录表实体（架构 M3-2：计费/观测落 SQLite，V4 迁移建表）。
 * 一行一次 AI 请求：工具名/请求文本/响应长度/耗时；V12 起补模型、token 与积分扣减（观测）。
 */
@Table("ai_usage_log")
@Getter
@Setter
public class AiUsageEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String toolName;
    private String requestText;
    private int responseLength;
    private int elapsedMs;
    private String model;
    private int inputTokens;
    private int outputTokens;
    private int pointsCost;
    private Long accountId;
    private String createdAt;
}
