package org.gms.data.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 账号表实体（架构 M1 登录校验，红线 2：newmaple 库兼容，表结构不改）。
 * Lombok 生成字段 getter/setter（红线 11，可变实体类用 @Getter/@Setter 代替手写）。
 *
 * <p>字段对齐参考项目 accounts 表（思路参考自 BeiDou-Server，结构按红线兼容）。
 * 关键语义：
 * <ul>
 *   <li>{@code banned} 只有值 1 表示已封禁；查询未封禁必须用 {@code banned <> 1}（红线 8，兼容 NULL）。</li>
 *   <li>{@code password} 为服务端存储的登录口令散列（M1 login 校验用）。</li>
 *   <li>{@code loggedin} 在线状态位（0=离线，1=在线）。</li>
 * </ul>
 *
 * <p>驼峰列名（{@code nxCredit} 等）在 SQLite/PG/MySQL 三方言下均需显式 {@link Column} 标注，
 * 避免 MyBatis-Flex 默认下划线转换（{@code nx_credit}）对不上真实列名。
 */
@Table("accounts")
@Getter
@Setter
public class Account {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String name;
    private String password;
    private String pin;
    private String pic;
    private int loggedin;
    private String lastlogin;
    private String createdat;
    private String birthday;
    /** 只有值 1 明确表示已封禁（红线 8）。 */
    private int banned;
    private String banreason;
    private String macs;
    @Column("nxCredit")
    private Integer nxCredit;
    @Column("maplePoint")
    private Integer maplePoint;
    @Column("nxPrepaid")
    private Integer nxPrepaid;
    private int characterslots;
    private int gender;
    private String tempban;
    private int greason;
    private int tos;
    private String sitelogged;
    private Integer webadmin;
    private String nick;
    private Integer mute;
    private String email;
    private String ip;
    private int rewardpoints;
    private int votepoints;
    private String hwid;
    private int language;
}
