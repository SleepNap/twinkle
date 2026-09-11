package org.gms.domain.game.logic;

import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.TradeItemSnapshot;
import java.util.List;
import java.util.Map;

/** ItemSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface ItemSystem {
    public enum ShopResult { SUCCESS, INVALID, NO_MONEY, NO_SPACE }

    public boolean changeMeso(CharacterState state, int delta);

    public boolean consumeRecovery(CharacterState state, short slot, int itemId, long now);

    public ShopResult buy(CharacterState state, int itemId, int quantity, int unitPrice);

    public ShopResult sell(CharacterState state, byte inventoryType, short slot, int itemId, int quantity);

    public static final int DEFAULT_SLOT_MAX = 100;

    public boolean giveItem(CharacterState state, int itemId, int quantity);

    public boolean takeItem(CharacterState state, int itemId, int quantity);

    public boolean canGiveItems(CharacterState state, Map<Integer, Integer> quantities);

    public int countItem(CharacterState state, int itemId);

    public boolean moveItem(CharacterState state, byte type, short source, short target, int quantity);

    public TradeItemSnapshot snapshotTradeItem(CharacterState state, byte inventoryType,
                                               short sourcePosition, int quantity);

    public boolean canExchangeTradeItems(CharacterState state,
                                         List<TradeItemSnapshot> outgoing,
                                         List<TradeItemSnapshot> incoming);

    public boolean takeTradeItems(CharacterState state, List<TradeItemSnapshot> items);

    public boolean giveTradeItems(CharacterState state, List<TradeItemSnapshot> items);
}
