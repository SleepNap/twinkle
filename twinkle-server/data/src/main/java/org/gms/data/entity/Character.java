package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色记录实体（架构 M1 选角 + 存档，数据库命名与迁移规范：表名 ≥2 词、字段 snake_case）。
 * Lombok 生成字段 getter/setter（红线 11，可变实体类用 @Getter/@Setter 代替手写）。
 *
 * <p>字段与 {@code character_records} 表对齐（全 snake_case 列名；MyBatis-Flex 驼峰→下划线
 * 自动匹配，无需 @Column 显式标注）。映射选角列表展示 + 存档读写所需字段。
 *
 * <p>{@code int_stat} 对应原 {@code "int"} 关键字列（规范改名，消除关键字字段）。
 */
@Table("character_records")
@Getter
@Setter
public class Character {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long accountId;
    private int world;
    private String name;
    private int level;
    private long exp;
    private long gachaExp;
    private short str;
    private short dex;
    private short luk;
    private short intStat;
    private short hp;
    private short mp;
    private short maxHp;
    private short maxMp;
    private int meso;
    private int hpMpUsed;
    private int job;
    private int skinColor;
    private int gender;
    private int fame;
    private int fQuest;
    private int hair;
    private int face;
    private int ap;
    private String sp;
    private int map;
    private int spawnPoint;
    private int gm;
    private int party;
    private int buddyCapacity;
    private String createDate;
    private long rank;
    private int rankMove;
    private long jobRank;
    private int jobRankMove;
    private int guildId;
    private int guildRank;
    private int messengerId;
    private int messengerPosition;
    private int mountLevel;
    private int mountExp;
    private int mountTiredness;
    private int omokWins;
    private int omokLosses;
    private int omokTies;
    private int matchCardWins;
    private int matchCardLosses;
    private int matchCardTies;
    private int merchantMesos;
    private int hasMerchant;
    private int equipSlots;
    private int useSlots;
    private int setupSlots;
    private int etcSlots;
    private int familyId;
    private int monsterBookCover;
    private int allianceRank;
    private int vanquisherStage;
    private int ariantPoints;
    private int dojoPoints;
    private int lastDojoStage;
    private int finishedDojoTutorial;
    private int vanquisherKills;
    private int summonValue;
    private int partnerId;
    private int marriageItemId;
    private int reborns;
    private int pqPoints;
    private String dataString;
    private String lastLogoutTime;
    private String lastExpGainTime;
    private int partySearch;
    private long jailExpire;
}
