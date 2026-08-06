package org.gms.domain.game.item;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * 物品静态数据（稳定层，纯数据）。Item.wz 解析填充（M2-3），
 * 背包/装备/商店逻辑经此查物品属性。手动 new、不进容器（红线 4）。
 *
 * <p>效果/能力统一存 {@code stats}（键如 hp/mp/hpr/watk/matk/str/dex），
 * 消费类来自 spec 节点、装备类来自 Equip info 的能力节点。
 */
@Getter
@Setter
public class ItemData implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final int itemId;
    private int price;
    /** 单槽堆叠上限（v83 默认 100）。 */
    private int slotMax = 100;
    private boolean tradeBlock;
    private int reqLevel;

    @Getter(AccessLevel.NONE)
    private final Map<String, Integer> stats = new HashMap<>();

    public ItemData(int itemId) {
        this.itemId = itemId;
    }

    public void putStat(String key, int value) {
        stats.put(key, value);
    }

    public Integer getStat(String key) {
        return stats.get(key);
    }

    /** 全部效果/能力（不可变视图）。 */
    public Map<String, Integer> stats() {
        return Map.copyOf(stats);
    }
}
