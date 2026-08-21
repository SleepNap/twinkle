package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 背包物品表实体（架构 M3-5 存档，数据库命名与迁移规范：字段 snake_case）。
 * 一角色一行一物品（含槽位/数量/所有者），回填内存态背包与落库共用。
 * 字段 camelCase，MyBatis-Flex 自动转 snake_case 列名匹配 {@code inventory_items}。
 */
@Table("inventory_items")
@Getter
@Setter
public class InventoryItemEntity {

    @Id(keyType = KeyType.Auto)
    private Long inventoryItemId;
    private int type;
    private int characterId;
    private int accountId;
    private int itemId;
    private int inventoryType;
    private int position;
    private int quantity;
    private String owner;
    private int petId;
    private int cashId;
    private int flag;
    /** Unix 毫秒时间戳；-1 表示永不过期，0 可能表示已失效或无效数据。 */
    private long expiration = -1;
    private String giftFrom;
    private int upgradeSlots;
    private int level;
    private int strStat;
    private int dexStat;
    private int intStat;
    private int lukStat;
    private int hp;
    private int mp;
    private int wAtk;
    private int mAtk;
    private int wDef;
    private int mDef;
    private int acc;
    private int avoid;
    private int hands;
    private int speed;
    private int jump;
    private int vicious;
    private int itemLevel;
    private long itemExp;
    private int ringId;
    private String petName = "";
    private int petLevel = 1;
    private int petCloseness;
    private int petFullness = 100;
    private int petAttribute;
    private int petSkill;
    private int petRemainLife = 18_000;
    private int itemAttribute;
}
