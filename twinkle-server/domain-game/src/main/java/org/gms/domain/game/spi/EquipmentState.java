package org.gms.domain.game.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 换装逻辑通过快照规划，再一次性提交槽位变化；不开放已穿戴物品的交易入口。 */
public interface EquipmentState extends CharacterState {
    public record SlotMove(short source, short target) { }

    public int getGender();
    public int getFame();
    public int equipmentSlotLimit();
    public Map<Short, TradeItemSnapshot> equipmentItems();
    public boolean applyEquipmentMoves(Map<Short, TradeItemSnapshot> expected,
                                       List<SlotMove> moves, Set<Short> bindSlots);
    public boolean removeEquipment(Map<Short, TradeItemSnapshot> expected, Set<Short> slots);

    /**
     * 把装备栏实例的属性替换为新的完整实例投影（卷轴强化）。
     *
     * <p>槽位可为已穿戴负槽或 `EQUIP` 背包正槽（匠人之魂）；槽位不存在、实例不是装备或
     * 装备栏已偏离 {@code expected} 时返回 false 且不修改；对象身份、期限、归属和现金 id
     * 仍保留在原实例上。
     */
    public default boolean updateEquipmentItem(Map<Short, TradeItemSnapshot> expected, short slot,
                                                TradeItemSnapshot replacement) {
        return false;
    }

    /**
     * 移除装备栏实例（卷轴诅咒销毁）；负槽为已穿戴装备，正槽为 `EQUIP` 背包物品。
     * 槽位不存在、对手不是装备或装备栏已偏离 {@code expected} 时返回 false。
     */
    public default boolean removeEquipmentItem(Map<Short, TradeItemSnapshot> expected, short slot) {
        return false;
    }

    public void setEquipmentStats(EquipmentStats stats);
}
