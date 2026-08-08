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
    // ---------- newmaple 兼容列（M5-2 单库迁移 V7 补列，对齐参考项目 queststatus 9 列） ----------
    // 老库导入时这四列有落点；twinkle 领域层当前不使用，保持默认 0 即对齐默认语义。
    private long expires;
    private int forfeited;
    private int completed;
    private int info;
}
