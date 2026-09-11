package org.gms.domain.game.logic;

import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.trade.Trade;
import org.gms.domain.game.trade.TradeSide;

/** TradeSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface TradeSystem {
    public enum ConfirmResult {
        REJECTED,
        WAITING,
        COMPLETED,
        FAILED
    }

    public Trade create(TradeSide first, TradeSide second);

    public boolean offer(Trade trade, CharacterState trader, byte inventoryType,
                         short sourcePosition, int quantity, byte targetSlot);

    public boolean offerMeso(Trade trade, CharacterState trader, int amount);

    public boolean lock(Trade trade, CharacterState trader);

    public ConfirmResult confirm(Trade trade, CharacterState trader);

    public boolean complete(Trade trade);

    public boolean interrupt(Trade trade);
}
