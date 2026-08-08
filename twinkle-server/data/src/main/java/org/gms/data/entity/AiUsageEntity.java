package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * AI 使用记录表实体（架构 M3-2：计费/观测落 SQLite，V4 迁移建表）。
 * 一行一次 AI 请求：工具名/请求文本/响应长度/耗时。
 */
@Table("ai_usage")
@Getter
@Setter
public class AiUsageEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String toolName;
    private String requestText;
    private int responseLength;
    private int elapsedMs;
    private String createdAt;
}
