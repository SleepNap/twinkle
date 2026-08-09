package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务进度表实体（架构 M3-5 存档，数据库命名与迁移规范：字段 snake_case）。
 * 任务进度的每行 = progressId → progress 值（QuestStatus.progress 的 Map 逐行落库）。
 * 字段 camelCase，MyBatis-Flex 自动转 snake_case 列名匹配 {@code quest_progress}。
 */
@Table("quest_progress")
@Getter
@Setter
public class QuestProgressEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private int characterId;
    private int questStatusId;
    private int progressId;
    private String progress;
}
