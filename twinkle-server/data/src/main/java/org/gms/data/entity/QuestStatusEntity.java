package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 任务状态表实体（架构 M3-5 存档，红线 2：newmaple queststatus 结构不变）。
 * Lombok 生成 getter/setter（红线 11）。
 */
@Table("queststatus")
@Getter
@Setter
public class QuestStatusEntity {

    @Id(keyType = KeyType.Auto)
    private Long queststatusid;
    private int characterid;
    private int quest;
    private int status;
    private int time;
}
