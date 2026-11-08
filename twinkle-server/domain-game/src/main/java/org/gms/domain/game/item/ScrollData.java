package org.gms.domain.game.item;

import java.util.Map;
import java.util.Set;

/**
 * Item.wz 卷轴的静态数据投影（稳定层，纯数据）。
 *
 * <p>{@code success}/{@code cursed} 是 v83 的百分比；{@code requiredItems} 来自卷轴的
 * {@code req} 子节点，非空时只允许列出的装备编号；{@code stats} 是 {@code incXXX}
 * 归一化后的增量（如 {@code incPAD -> watk}），与装备模板属性使用同一组键。
 */
public record ScrollData(int success, int cursed, Set<Integer> requiredItems, Map<String, Integer> stats) {

    public ScrollData {
        success = Math.clamp(success, 0, 100);
        cursed = Math.clamp(cursed, 0, 100);
        requiredItems = Set.copyOf(requiredItems);
        stats = Map.copyOf(stats);
    }

    /** 空白 req 交给卷轴类别规则判断；非空 req 只允许列出的装备。 */
    public boolean allows(int equipmentItemId) {
        return requiredItems.isEmpty() || requiredItems.contains(equipmentItemId);
    }
}
