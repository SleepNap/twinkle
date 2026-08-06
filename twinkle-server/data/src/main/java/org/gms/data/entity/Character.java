package org.gms.data.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 角色表实体（架构 M1 选角，红线 3：characters 结构不变）。
 * Lombok 生成字段 getter/setter（红线 11，可变实体类用 @Getter/@Setter 代替手写）。
 *
 * <p>M1 映射选角列表展示所需字段（id/accountid/world/name/属性/外观/rank）。
 * 其余列（sp 之外的 mount/dojo 等）待 M2 游戏逻辑接入时补充映射，表结构保持完整
 * （V2 迁移建全列）。
 *
 * <p>{@code int} 是 SQL 保留字列名，Java 字段用 {@code intStat} + {@link Column} 显式映射；
 * {@code hpMpUsed} 等驼峰列同理需显式标注。字段对齐参考项目 characters 表
 * （思路参考自 BeiDou-Server，结构按红线兼容）。
 */
@Table("characters")
@Getter
@Setter
public class Character {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private Long accountid;
    private int world;
    private String name;
    private int level;
    private long exp;
    private long gachaexp;
    private short str;
    private short dex;
    private short luk;
    @Column("int")
    private short intStat;
    private short hp;
    private short mp;
    private short maxhp;
    private short maxmp;
    private int meso;
    private int job;
    private int skincolor;
    private int gender;
    private int fame;
    private int hair;
    private int face;
    private int ap;
    private String sp;
    private int map;
    private int spawnpoint;
    private int gm;
    private long rank;
    @Column("rankMove")
    private int rankMove;
    @Column("jobRank")
    private long jobRank;
    @Column("jobRankMove")
    private int jobRankMove;

    // ---------- 存档剩余列（M2 进图/游戏逻辑接入；表结构对齐 newmaple，红线 3） ----------
    // MyBatis-Flex 默认驼峰→下划线列名，与 DB 驼峰列名不符的字段需 @Column 显式对齐
    // （全小写字段如 guildid/omokwins 默认列名=字段名=DB 列名，无需标注）。
    @Column("hpMpUsed")
    private int hpMpUsed;
    private int fquest;
    private int party;
    @Column("buddyCapacity")
    private int buddyCapacity;
    private String createdate;
    private int guildid;
    private int guildrank;
    private int messengerid;
    private int messengerposition;
    private int mountlevel;
    private int mountexp;
    private int mounttiredness;
    private int omokwins;
    private int omoklosses;
    private int omokties;
    private int matchcardwins;
    private int matchcardlosses;
    private int matchcardties;
    @Column("MerchantMesos")
    private int MerchantMesos;
    @Column("HasMerchant")
    private int HasMerchant;
    private int equipslots;
    private int useslots;
    private int setupslots;
    private int etcslots;
    @Column("familyId")
    private int familyId;
    private int monsterbookcover;
    @Column("allianceRank")
    private int allianceRank;
    @Column("vanquisherStage")
    private int vanquisherStage;
    @Column("ariantPoints")
    private int ariantPoints;
    @Column("dojoPoints")
    private int dojoPoints;
    @Column("lastDojoStage")
    private int lastDojoStage;
    @Column("finishedDojoTutorial")
    private int finishedDojoTutorial;
    @Column("vanquisherKills")
    private int vanquisherKills;
    @Column("summonValue")
    private int summonValue;
    @Column("partnerId")
    private int partnerId;
    @Column("marriageItemId")
    private int marriageItemId;
    private int reborns;
    @Column("PQPoints")
    private int PQPoints;
    @Column("dataString")
    private String dataString;
    @Column("lastLogoutTime")
    private String lastLogoutTime;
    @Column("lastExpGainTime")
    private String lastExpGainTime;
    @Column("partySearch")
    private int partySearch;
    private long jailexpire;
}
