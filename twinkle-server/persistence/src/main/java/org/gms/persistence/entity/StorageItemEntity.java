package org.gms.persistence.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 账号仓库物品行实体（storage/trunk 持久化，V19 迁移建表 {@code storage_items}）。
 *
 * <p>列集与 {@link InventoryItemEntity} 对齐（V3 物品列 + V11 宠物列），差别只有归属键：
 * 仓库行挂 {@code storage_record_id}（账号级仓库头），槽位列名用 {@code slot_index}
 * （{@code position} 是 SQL 关键字）；同一仓库同一栏目内槽位唯一，由
 * {@code idx_storage_items_slot} 约束。
 *
 * <p>{@code owner}/{@code giftFrom} 给空串默认值：迁移把这些列定义为 NOT NULL，
 * MyBatis-Flex 的 {@code insert} 会把 null 字段一并写入，留空会撞 NOT NULL。
 */
@Table("storage_items")
@Getter
@Setter
public class StorageItemEntity {

    @Id(keyType = KeyType.Auto)
    private Long storageItemId;
    private long storageRecordId;
    private int accountId;
    private int inventoryType;
    private int slotIndex;
    private int itemId;
    private int quantity;
    private String owner = "";
    private int petId;
    private int flag;
    /** Unix 毫秒时间戳；-1 表示永不过期，0 可能表示已失效或无效数据。 */
    private long expiration = -1;
    private String giftFrom = "";
    private int cashId;
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
