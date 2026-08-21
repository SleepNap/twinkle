package org.gms.data.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/** v83 兼容 skills 表实体。 */
@Table("skills")
@Getter
@Setter
public final class SkillEntity {

    @Id(keyType = KeyType.Auto)
    private Long id;
    @Column("skillid")
    private int skillId;
    @Column("characterid")
    private int characterId;
    @Column("skilllevel")
    private int skillLevel;
    @Column("masterlevel")
    private int masterLevel;
    /** Unix 毫秒时间戳；-1 表示永不过期，0 不具有永久语义。 */
    private long expiration = -1;
}
