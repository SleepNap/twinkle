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
     * 把已穿戴装备的属性替换为新的完整实例投影（卷轴强化）。
     *
     * <p>槽位不是已穿戴的负数槽、实例不是装备、或装备栏已偏离 {@code expected} 时返回
     * false 且不修改；对象身份、期限、归属和现金 id 仍保留在原实例上。
     */
    public default boolean updateEquipmentItem(Map<Short, TradeItemSnapshot> expected, short slot,
                                                TradeItemSnapshot replacement) {
        return false;
    }

    public void setEquipmentStats(EquipmentStats stats);
}
