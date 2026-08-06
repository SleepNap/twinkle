package org.gms.domain.script.host;

/**
 * 宿主对象契约 im（item manager，架构 M0 第 9 项）。
 *
 * <p>脚本通过 {@code im} 操作物品（给/收/查数量）。
 * 物品具体值/Item.wz 数据留 M2-3 余项，本契约先声明入口。
 */
public interface Im {

    /** 给角色物品（quantity ≥ 1）。 */
    void giveItem(int itemId, int quantity);

    /** 收物品（quantity ≥ 1；持有不足则返回 0、否则返回实际扣除数）。 */
    int takeItem(int itemId, int quantity);

    /** 持有数量。 */
    int getItemCount(int itemId);
}
