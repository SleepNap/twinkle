package org.gms.domain.game.trade;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.gms.domain.game.spi.CharacterState;

import java.util.HashMap;
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
    private final Map<Integer, Integer> offeredItems = new HashMap<>();

    private int meso;
    private boolean locked;

    public TradeSide(CharacterState trader) {
        this.trader = trader;
    }

    /** 添加出价物品（同物品合并数量）。 */
    public void offerItem(int itemId, int quantity) {
        offeredItems.merge(itemId, quantity, Integer::sum);
    }

    /** 清空出价物品（显式中断交易时回滚用：物品从未离背包，清 offer 即归位）。 */
    public void clearOffer() {
        offeredItems.clear();
    }

    /** 出价物品（不可变视图：itemId → quantity）。 */
    public Map<Integer, Integer> offeredItems() {
        return Map.copyOf(offeredItems);
    }
}
