package org.gms.domain.game.trade;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.TradeItemSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易一方（稳定层，纯数据状态）。出价（物品/金币）+ 锁定标记。
 * 交易逻辑在可替换层 {@code TradeSystem}，本类只持有状态。
 */
@Getter
@Setter
public final class TradeSide {

    private final CharacterState trader;

    @Getter(AccessLevel.NONE)
    private final Map<Byte, TradeItemSnapshot> offeredItems = new LinkedHashMap<>();

    private int meso;
    private boolean locked;

    public TradeSide(CharacterState trader) {
        this.trader = trader;
    }

    /**
     * 添加精确槽位出价。交易窗口目标槽和背包源槽均只能使用一次，防止重复承诺同一实例。
     */
    public boolean offerItem(byte targetSlot, TradeItemSnapshot item) {
        if (item == null || Byte.toUnsignedInt(targetSlot) > 8 || offeredItems.containsKey(targetSlot)) {
            return false;
        }
        for (TradeItemSnapshot offered : offeredItems.values()) {
            if (offered.inventoryType() == item.inventoryType()
                    && offered.sourcePosition() == item.sourcePosition()) {
                return false;
            }
        }
        offeredItems.put(targetSlot, item);
        return true;
    }

    /** 清空出价物品（显式中断交易时回滚用：物品从未离背包，清 offer 即归位）。 */
    public void clearOffer() {
        offeredItems.clear();
    }

    /** 出价物品（按交易窗口槽位加入顺序的不可变视图）。 */
    public List<TradeItemSnapshot> offeredItems() {
        return List.copyOf(offeredItems.values());
    }
}
