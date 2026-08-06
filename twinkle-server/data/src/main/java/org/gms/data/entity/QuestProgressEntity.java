package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务进度表实体（架构 M3-5 存档，红线 2：newmaple questprogress 结构不变）。
 * 任务进度的每行 = progressid → progress 值（QuestStatus.progress 的 Map 逐行落库）。
 */
@Table("questprogress")
@Getter
@Setter
public class QuestProgressEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private int characterid;
    private int queststatusid;
    private int progressid;
    private String progress;
}
