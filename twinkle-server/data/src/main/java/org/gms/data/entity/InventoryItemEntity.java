package org.gms.data.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 背包物品表实体（架构 M3-5 存档，红线 2：newmaple inventoryitems 结构不变）。
 * 一角色一行一物品（含槽位/数量/所有者），回填内存态背包与落库共用。
 */
@Table("inventoryitems")
@Getter
@Setter
public class InventoryItemEntity {

    @Id(keyType = KeyType.Auto)
    private Long inventoryitemid;
    private int type;
    private int characterid;
    private int accountid;
    private int itemid;
    private int inventorytype;
    private int position;
    private int quantity;
    private String owner;
    private int petid;
    private int flag;
    private long expiration;
    private String giftFrom;
}
