package org.gms.persistence.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 公会头表实体（V20 迁移建表 {@code guild_record}）。
 *
 * <p>成员关系不在这张表里：{@code character_records.guild_id}/{@code guild_rank} 是成员的唯一权威
 * （见 V2 迁移），本表只保存公会级数据，避免「公会成员表 + 角色表」双写漂移。
 *
 * <p>{@code row_version} 是乐观锁版本：跨频道写入必须带期望版本，成功后 +1；版本不一致说明
 * 另一个频道已经写过，写方丢弃自己的频道缓存并重读，而不是覆盖。
 */
@Table("guild_record")
@Getter
@Setter
public class GuildRecordEntity {

    @Id(keyType = KeyType.Auto)
    private Long guildRecordId;
    private int world;
    private String name = "";
    private long leaderId;
    /** 成员上限，v83 默认 10，后续由 NPC 脚本扩容。 */
    private int capacity = 10;
    private int pointGp;
    // 数字也是独立 token：列名是 rank_title_1..5，默认驼峰转换会得到 rank_title1，必须显式指定。
    @Column("rank_title_1")
    private String rankTitle1 = "Master";
    @Column("rank_title_2")
    private String rankTitle2 = "Jr. Master";
    @Column("rank_title_3")
    private String rankTitle3 = "Member";
    @Column("rank_title_4")
    private String rankTitle4 = "Member";
    @Column("rank_title_5")
    private String rankTitle5 = "Member";
    private int logoBackground;
    private int logoBackgroundColor;
    private int logoMark;
    private int logoMarkColor;
    private String notice = "";
    private int signature;
    private int allianceId;
    private int rowVersion;
    private String updatedAt;
}
