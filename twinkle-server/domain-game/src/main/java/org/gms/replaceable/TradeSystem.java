package org.gms.replaceable;

import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.trade.Trade;
import org.gms.domain.game.trade.TradeSide;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

/**
 * 交易系统（可替换层，架构第三节状态/逻辑分离 + 红线 8/11/12）。
 *
 * <p>状态机（Trade/TradeSide）在稳定层，本系统管业务判定与结算。物品转移经
 * {@link ItemSystem}（背包经 CharacterState 接口）；meso 经接口读写。
 * 跨 tick 操作——交易是热重载风险点（架构 5.3），单实体安全点设计，结算前
 * 过版本门判定。
 */
public final class TradeSystem {

    private final VersionGate versionGate;
    private final ItemSystem itemSystem;

    public TradeSystem(VersionGate versionGate, ItemSystem itemSystem) {
        this.versionGate = versionGate;
        this.itemSystem = itemSystem;
    }

    public Trade create(TradeSide first, TradeSide second) {
        return new Trade(first, second);
    }

    /** 添加出价物品（ACTIVE + 未锁定 + 持有足够）。 */
    public boolean offer(Trade trade, CharacterState trader, int itemId, int quantity) {
        if (versionGate.decide(trader) != VersionDecision.ALLOW) {
            return false;
        }
        if (trade.getState() != Trade.State.ACTIVE || quantity <= 0) {
            return false;
        }
        TradeSide side = trade.sideOf(trader);
        if (side == null || side.isLocked()) {
            return false;
        }
        if (itemSystem.countItem(trader, itemId) < quantity) {
            return false;
        }
        side.offerItem(itemId, quantity);
        return true;
    }

    /** 添加出价金币（v83 交易含 meso）。 */
    public boolean offerMeso(Trade trade, CharacterState trader, int amount) {
        if (versionGate.decide(trader) != VersionDecision.ALLOW) {
            return false;
        }
        if (trade.getState() != Trade.State.ACTIVE || amount < 0) {
            return false;
        }
        TradeSide side = trade.sideOf(trader);
        if (side == null || side.isLocked()) {
            return false;
        }
        if (trader.getMeso() < amount) {
            return false;
        }
        side.setMeso(amount);
        return true;
    }

    /** 锁定出价（锁定后不可再改）。 */
    public boolean lock(Trade trade, CharacterState trader) {
        TradeSide side = trade.sideOf(trader);
        if (side == null || side.isLocked()) {
            return false;
        }
        side.setLocked(true);
        return true;
    }

    /** 双方锁定后结算：交换物品与 meso。 */
    public boolean complete(Trade trade) {
        if (trade.getState() != Trade.State.ACTIVE || !trade.bothLocked()) {
            return false;
        }
        if (!settle(trade)) {
            trade.setState(Trade.State.CANCELLED);
            return false;
        }
        trade.setState(Trade.State.DONE);
        return true;
    }

    private boolean settle(Trade trade) {
        return transfer(trade.getFirst(), trade.getSecond())
                && transfer(trade.getSecond(), trade.getFirst());
    }

    /** 一方 → 另一方（物品 + meso）。单线程下 offer 已校验持有，take 不会失败；give 容量不足返回 false。 */
    private boolean transfer(TradeSide from, TradeSide to) {
        for (var e : from.offeredItems().entrySet()) {
            if (!itemSystem.takeItem(from.getTrader(), e.getKey(), e.getValue())) {
                return false;
            }
            if (!itemSystem.giveItem(to.getTrader(), e.getKey(), e.getValue())) {
                return false;
            }
        }
        if (from.getMeso() > 0) {
            to.getTrader().setMeso(to.getTrader().getMeso() + from.getMeso());
            from.getTrader().setMeso(from.getTrader().getMeso() - from.getMeso());
        }
        return true;
    }
}
