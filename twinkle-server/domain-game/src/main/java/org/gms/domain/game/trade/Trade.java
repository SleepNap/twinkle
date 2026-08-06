package org.gms.domain.game.trade;

import lombok.Getter;
import lombok.Setter;
import org.gms.domain.game.spi.CharacterState;

/**
 * 交易会话（稳定层，纯数据状态机）。状态：ACTIVE（进行中）→ 双方锁定 →
 * DONE（结算完成）/ CANCELLED（结算失败）。
 * 交易逻辑在可替换层 {@code TradeSystem}，本类只持有状态与参与者。
 */
@Getter
@Setter
public final class Trade {

    /** 交易状态。 */
    public enum State { ACTIVE, DONE, CANCELLED }

    private State state = State.ACTIVE;
    private final TradeSide first;
    private final TradeSide second;

    public Trade(TradeSide first, TradeSide second) {
        this.first = first;
        this.second = second;
    }

    /** 按参与者取一方；不属于本交易返回 null。 */
    public TradeSide sideOf(CharacterState trader) {
        if (first.getTrader() == trader) {
            return first;
        }
        if (second.getTrader() == trader) {
            return second;
        }
        return null;
    }

    /** 双方都已锁定。 */
    public boolean bothLocked() {
        return first.isLocked() && second.isLocked();
    }
}
