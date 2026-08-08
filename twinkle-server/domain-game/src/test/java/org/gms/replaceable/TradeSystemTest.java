package org.gms.replaceable;

import org.gms.domain.game.Character;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.trade.Trade;
import org.gms.domain.game.trade.TradeSide;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交易系统：出价/锁定/结算（物品与 meso 双向转移）+ 状态机约束 + 版本门。
 */
class TradeSystemTest {

    private final VersionGate versionGate = new DefaultVersionGate();
    private final Map<Integer, ItemData> itemData = Map.of(2_000_000, item(2_000_000, 100));
    private final ItemSystem itemSystem = new ItemSystem(versionGate, itemData);
    private final TradeSystem tradeSystem = new TradeSystem(versionGate, itemSystem);

    private static ItemData item(int id, int slotMax) {
        ItemData d = new ItemData(id);
        d.setSlotMax(slotMax);
        return d;
    }

    private Character player(int meso) {
        Character c = new Character(versionGate.currentVersion());
        c.setMeso(meso);
        return c;
    }

    private void give(Character c, int id, int qty) {
        assertThat(itemSystem.giveItem(c, id, qty)).isTrue();
    }

    @Test
    @DisplayName("双方锁定后结算：物品双向转移")
    void completeTransfersItemsBothWays() {
        Character a = player(0);
        Character b = player(0);
        give(a, 2_000_000, 50);
        give(b, 2_000_000, 20);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(tradeSystem.offer(trade, a, 2_000_000, 30)).isTrue();
        assertThat(tradeSystem.offer(trade, b, 2_000_000, 10)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.lock(trade, b)).isTrue();
        assertThat(tradeSystem.complete(trade)).isTrue();

        // a: 50 - 30 + 10 = 30；b: 20 - 10 + 30 = 40
        assertThat(itemSystem.countItem(a, 2_000_000)).isEqualTo(30);
        assertThat(itemSystem.countItem(b, 2_000_000)).isEqualTo(40);
        assertThat(trade.getState()).isEqualTo(Trade.State.DONE);
    }

    @Test
    @DisplayName("meso 双向转移")
    void mesoTransferred() {
        Character a = player(1000);
        Character b = player(500);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(tradeSystem.offerMeso(trade, a, 300)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.lock(trade, b)).isTrue();
        assertThat(tradeSystem.complete(trade)).isTrue();

        assertThat(a.getMeso()).isEqualTo(700);
        assertThat(b.getMeso()).isEqualTo(800);
    }

    @Test
    @DisplayName("锁定后拒绝继续出价")
    void offerRejectedAfterLock() {
        Character a = player(0);
        give(a, 2_000_000, 10);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(player(0)));
        assertThat(tradeSystem.offer(trade, a, 2_000_000, 5)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.offer(trade, a, 2_000_000, 3)).isFalse();
    }

    @Test
    @DisplayName("一方未锁定则不能结算")
    void completeRejectedUntilBothLock() {
        Character a = player(0);
        give(a, 2_000_000, 10);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(player(0)));
        assertThat(tradeSystem.offer(trade, a, 2_000_000, 5)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.complete(trade)).isFalse();     // b 未锁定
        assertThat(trade.getState()).isEqualTo(Trade.State.ACTIVE);
    }

    @Test
    @DisplayName("出价超持有量拒绝")
    void offerExceedingHeldRejected() {
        Character a = player(0);
        give(a, 2_000_000, 5);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(player(0)));
        assertThat(tradeSystem.offer(trade, a, 2_000_000, 6)).isFalse();
    }

    @Test
    @DisplayName("版本门拒绝换代后的迟到出价")
    void versionGateBlocksStaleOffer() {
        Character a = new Character(versionGate.currentVersion());
        give(a, 2_000_000, 10);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(player(0)));
        versionGate.onReload();
        assertThat(tradeSystem.offer(trade, a, 2_000_000, 5)).isFalse();
    }

    // ---- 架构 5.3：显式中断 + 回滚（可感知极限 = 交易被取消） ----

    @Test
    @DisplayName("中断 ACTIVE 交易：出价清空、meso 归零、状态 CANCELLED、背包不动")
    void interrupt_activeTrade_clearsOffersAndCancels() {
        Character a = player(1000);
        Character b = player(1000);
        give(a, 2_000_000, 10);
        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        tradeSystem.offer(trade, a, 2_000_000, 5);
        tradeSystem.offerMeso(trade, b, 300);

        assertThat(tradeSystem.interrupt(trade)).isTrue();

        assertThat(trade.getState()).isEqualTo(Trade.State.CANCELLED);
        assertThat(trade.getFirst().offeredItems()).isEmpty();
        assertThat(trade.getFirst().getMeso()).isZero();
        assertThat(trade.getSecond().offeredItems()).isEmpty();
        assertThat(trade.getSecond().getMeso()).isZero();
        // 背包归位：物品未离开背包（offer 只是承诺），meso 未动
        assertThat(a.getItemCount(2_000_000)).isEqualTo(10);
        assertThat(a.getMeso()).isEqualTo(1000);
        assertThat(b.getMeso()).isEqualTo(1000);
    }

    @Test
    @DisplayName("中断已结束的交易返回 false（幂等）")
    void interrupt_doneTrade_returnsFalse() {
        Character a = player(1000);
        Character b = player(1000);
        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        tradeSystem.lock(trade, a);
        tradeSystem.lock(trade, b);
        assertThat(tradeSystem.complete(trade)).isTrue();
        assertThat(trade.getState()).isEqualTo(Trade.State.DONE);

        assertThat(tradeSystem.interrupt(trade)).isFalse();
        assertThat(trade.getState()).isEqualTo(Trade.State.DONE);
    }

    @Test
    @DisplayName("中断后可重新发起新交易（安全点清空）")
    void interrupt_clearsStateForNewTrade() {
        Character a = player(1000);
        Character b = player(1000);
        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        tradeSystem.offerMeso(trade, a, 500);
        assertThat(tradeSystem.interrupt(trade)).isTrue();
        assertThat(trade.getState()).isEqualTo(Trade.State.CANCELLED);
        assertThat(a.getMeso()).isEqualTo(1000);   // 回滚后 meso 归位
    }
}
