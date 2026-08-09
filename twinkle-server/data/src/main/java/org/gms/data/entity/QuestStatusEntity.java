package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务状态表实体（架构 M3-5 存档，数据库命名与迁移规范：字段 snake_case）。
 * 九列一次建全（原五列 + expires/forfeited/completed/info 四列，避免后续 ALTER）。
 * 字段 camelCase，MyBatis-Flex 自动转 snake_case 列名匹配 {@code quest_status}。
 */
@Table("quest_status")
@Getter
@Setter
public class QuestStatusEntity {

    @Id(keyType = KeyType.Auto)
    private Long questStatusId;
    private int characterId;
    private int quest;
    private int status;
    private int time;
    private long expires;
    private int forfeited;
    private int completed;
    private int info;
}
