package org.gms.replaceable;

import org.gms.domain.game.spi.CharacterState;
import org.gms.domain.game.spi.TradeItemSnapshot;
import org.gms.domain.game.trade.Trade;
import org.gms.domain.game.trade.TradeSide;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.i18n.I18n;

/**
 * 交易系统（可替换层，架构第三节状态/逻辑分离 + 红线 8/11/12）。
 *
 * <p>状态机（Trade/TradeSide）在稳定层，本系统管业务判定与结算。物品转移经
 * {@link ItemSystem}（背包经 CharacterState 接口）；meso 经接口读写。
 * 跨 tick 操作——交易是热重载风险点（架构 5.3），单实体安全点设计，结算前
 * 过版本门判定。
 */
public final class TradeSystem {

    /** 单次确认的原子结果，供两个网络会话并发确认时避免重复结算。 */
    public enum ConfirmResult {
        REJECTED,
        WAITING,
        COMPLETED,
        FAILED
    }

    private final VersionGate versionGate;
    private final ItemSystem itemSystem;

    public TradeSystem(VersionGate versionGate, ItemSystem itemSystem) {
        this.versionGate = versionGate;
        this.itemSystem = itemSystem;
    }

    public Trade create(TradeSide first, TradeSide second) {
        return new Trade(first, second);
    }

    /** 添加精确背包槽位的出价物品（ACTIVE + 未锁定 + 实例有效）。 */
    public boolean offer(Trade trade, CharacterState trader, byte inventoryType,
                         short sourcePosition, int quantity, byte targetSlot) {
        if (versionGate.decide(trader) != VersionDecision.ALLOW) {
            return false;
        }
        synchronized (trade) {
            if (trade.getState() != Trade.State.ACTIVE || quantity <= 0) {
                return false;
            }
            TradeSide side = trade.sideOf(trader);
            if (side == null || side.isLocked()) {
                return false;
            }
            TradeItemSnapshot item = itemSystem.snapshotTradeItem(
                    trader, inventoryType, sourcePosition, quantity);
            if (item == null) {
                return false;
            }
            return side.offerItem(targetSlot, item);
        }
    }

    /** 添加出价金币（v83 交易含 meso）。 */
    public boolean offerMeso(Trade trade, CharacterState trader, int amount) {
        if (versionGate.decide(trader) != VersionDecision.ALLOW) {
            return false;
        }
        synchronized (trade) {
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
    }

    /** 锁定出价（锁定后不可再改）。 */
    public boolean lock(Trade trade, CharacterState trader) {
        synchronized (trade) {
            TradeSide side = trade.sideOf(trader);
            if (trade.getState() != Trade.State.ACTIVE || side == null || side.isLocked()) {
                return false;
            }
            side.setLocked(true);
            return true;
        }
    }

    /**
     * 原子执行“本方锁定 → 判断双方锁定 → 至多一次结算”。
     *
     * <p>两个玩家位于不同 Netty EventLoop，确认包可能同时到达；若把 lock、bothLocked 和
     * complete 拆成三步，两个线程都可能观察到双方已锁并重复扣除。以 Trade 实例为锁把
     * 状态转换和结算合成单一临界区。
     */
    public ConfirmResult confirm(Trade trade, CharacterState trader) {
        synchronized (trade) {
            TradeSide side = trade.sideOf(trader);
            if (trade.getState() != Trade.State.ACTIVE || side == null || side.isLocked()) {
                return ConfirmResult.REJECTED;
            }
            side.setLocked(true);
            if (!trade.bothLocked()) {
                return ConfirmResult.WAITING;
            }
            if (!settle(trade)) {
                trade.setState(Trade.State.CANCELLED);
                return ConfirmResult.FAILED;
            }
            trade.setState(Trade.State.DONE);
            return ConfirmResult.COMPLETED;
        }
    }

    /** 双方锁定后结算：交换物品与 meso。 */
    public boolean complete(Trade trade) {
        synchronized (trade) {
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
    }

    /**
     * 显式中断交易（架构 5.3：长操作在重载安全点等其自然结束或显式中断 + 回滚）。
     *
     * <p>交易天然可回滚：出价物品从未离开背包（offer 只是记录承诺），中断即清空
     * 双方出价并置 CANCELLED——玩家看到"交易被取消"，背包归位（可感知极限 = 交易被
     * 中断，不是东西没了/多出来了）。
     *
     * @return 是否确实中断了一个 ACTIVE 交易（已结束/未开始返回 false）
     */
    public boolean interrupt(Trade trade) {
        synchronized (trade) {
            if (trade.getState() != Trade.State.ACTIVE) {
                return false;
            }
            trade.getFirst().clearOffer();
            trade.getFirst().setMeso(0);
            trade.getFirst().setLocked(false);
            trade.getSecond().clearOffer();
            trade.getSecond().setMeso(0);
            trade.getSecond().setLocked(false);
            trade.setState(Trade.State.CANCELLED);
            return true;
        }
    }

    private boolean settle(Trade trade) {
        CharacterState first = trade.getFirst().getTrader();
        CharacterState second = trade.getSecond().getTrader();
        CharacterState lower = first.getId() <= second.getId() ? first : second;
        CharacterState higher = lower == first ? second : first;
        synchronized (lower) {
            synchronized (higher) {
                if (!canSettle(trade)) {
                    return false;
                }
                transferItems(trade);
                int firstMeso = first.getMeso() - trade.getFirst().getMeso() + trade.getSecond().getMeso();
                int secondMeso = second.getMeso() - trade.getSecond().getMeso() + trade.getFirst().getMeso();
                first.setMeso(firstMeso);
                second.setMeso(secondMeso);
                return true;
            }
        }
    }

    private boolean canSettle(Trade trade) {
        TradeSide first = trade.getFirst();
        TradeSide second = trade.getSecond();
        if (!hasOffer(first) || !hasOffer(second)) {
            return false;
        }
        long firstMeso = (long) first.getTrader().getMeso() - first.getMeso() + second.getMeso();
        long secondMeso = (long) second.getTrader().getMeso() - second.getMeso() + first.getMeso();
        if (firstMeso < 0 || firstMeso > Integer.MAX_VALUE
                || secondMeso < 0 || secondMeso > Integer.MAX_VALUE) {
            return false;
        }
        return itemSystem.canExchangeTradeItems(first.getTrader(),
                first.offeredItems(), second.offeredItems())
                && itemSystem.canExchangeTradeItems(second.getTrader(),
                second.offeredItems(), first.offeredItems());
    }

    private boolean hasOffer(TradeSide side) {
        if (side.getMeso() < 0 || side.getTrader().getMeso() < side.getMeso()) {
            return false;
        }
        return true;
    }

    /**
     * 预检成功后先移出双方物品，再接收双方物品，使容量判断中的“出槽后入槽”语义与实际一致。
     */
    private void transferItems(Trade trade) {
        TradeSide first = trade.getFirst();
        TradeSide second = trade.getSecond();
        if (!itemSystem.takeTradeItems(first.getTrader(), first.offeredItems())) {
            throw new IllegalStateException(I18n.message("error.trade.remove_initiator_items_failed"));
        }
        if (!itemSystem.takeTradeItems(second.getTrader(), second.offeredItems())) {
            throw new IllegalStateException(I18n.message("error.trade.remove_acceptor_items_failed"));
        }
        if (!itemSystem.giveTradeItems(second.getTrader(), first.offeredItems())) {
            throw new IllegalStateException(I18n.message("error.trade.acceptor_receive_failed"));
        }
        if (!itemSystem.giveTradeItems(first.getTrader(), second.offeredItems())) {
            throw new IllegalStateException(I18n.message("error.trade.initiator_receive_failed"));
        }
    }
}
