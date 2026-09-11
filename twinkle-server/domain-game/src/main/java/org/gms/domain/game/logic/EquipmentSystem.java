package org.gms.domain.game.logic;

import org.gms.domain.game.spi.EquipmentState;
import org.gms.domain.game.spi.EquipmentState.SlotMove;
import org.gms.domain.game.spi.TradeItemSnapshot;
import java.util.List;
import java.util.Set;

/** EquipmentSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface EquipmentSystem {
    public record Change(List<SlotMove> moves, Set<Short> boundSlots) {
        public Change { moves = List.copyOf(moves); boundSlots = Set.copyOf(boundSlots); }
    }

    public long dataVersion();

    public Change move(EquipmentState state, short source, short target, int quantity, long now);

    public boolean expire(EquipmentState state, long now);

    public boolean refresh(EquipmentState state, long now);
}
