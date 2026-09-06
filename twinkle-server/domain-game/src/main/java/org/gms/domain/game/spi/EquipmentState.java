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
    public void setEquipmentStats(EquipmentStats stats);
}
