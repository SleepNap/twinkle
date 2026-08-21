package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 账号记录实体（架构 M1 登录校验，数据库命名与迁移规范：表名 ≥2 词、字段 snake_case）。
 * Lombok 生成字段 getter/setter（红线 11，可变实体类用 @Getter/@Setter 代替手写）。
 *
 * <p>字段与 {@code account_records} 表对齐（全 snake_case，MyBatis-Flex 驼峰→下划线自动匹配，
 * 无需 @Column 显式标注）。关键语义：
 * <ul>
 *   <li>{@code banned} 只有值 1 表示已封禁；查询未封禁必须用 {@code banned <> 1}（红线 8，兼容 NULL）。</li>
 *   <li>{@code password} 为服务端存储的登录口令散列（M1 login 校验用）。</li>
 *   <li>{@code loggedIn} 在线状态位（0=离线，1=在线）。</li>
 * </ul>
 */
@Table("account_records")
@Getter
@Setter
public class Account {

    @Id(keyType = KeyType.Auto)
    private Long id;
    private String name;
    private String password;
    /** 一次性临时登录口令的 BCrypt 摘要；明文永不落库。 */
    private String temporaryPasswordHash;
    /** ISO-8601 UTC 过期时间；空值表示没有临时口令。 */
    private String temporaryPasswordExpiresAt;
    private String pin;
    private String pic;
    private int loggedIn;
    private String lastLogin;
    private String createdAt;
    private String birthday;
    /** 只有值 1 明确表示已封禁（红线 8）。 */
    private int banned;
    private String banReason;
    private String macAddresses;
    private Integer nxCredit;
    private Integer maplePoint;
    private Integer nxPrepaid;
    private int characterSlots;
    private int gender;
    private String tempBan;
    private int tos;
    private String siteLogged;
    private Integer webAdmin;
    private String nick;
    private Integer mute;
    private String email;
    private String ip;
    private int rewardPoints;
    private int votePoints;
    private String hwid;
    private int language;
}
