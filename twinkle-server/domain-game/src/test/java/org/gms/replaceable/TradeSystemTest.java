package org.gms.replaceable;

import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.inventory.ItemConstants;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.trade.Trade;
import org.gms.domain.game.trade.TradeSide;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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

    private boolean offer(Trade trade, Character trader, int itemId, int quantity, int targetSlot) {
        InventoryType type = ItemConstants.getInventoryType(itemId);
        Item source = trader.getInventory(type).items().stream()
                .filter(item -> item.getId() == itemId && item.getQuantity() >= quantity)
                .findFirst()
                .orElse(null);
        return source != null && tradeSystem.offer(trade, trader, type.getType(),
                source.getPosition(), quantity, (byte) targetSlot);
    }

    @Test
    @DisplayName("双方锁定后结算：物品双向转移")
    void completeTransfersItemsBothWays() {
        Character a = player(0);
        Character b = player(0);
        give(a, 2_000_000, 50);
        give(b, 2_000_000, 20);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(offer(trade, a, 2_000_000, 30, 0)).isTrue();
        assertThat(offer(trade, b, 2_000_000, 10, 0)).isTrue();
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
    @DisplayName("两个网络会话并发确认时只结算一次")
    void concurrentConfirmSettlesExactlyOnce() {
        Character a = player(5000);
        Character b = player(5000);
        give(a, 2_000_000, 10);
        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(offer(trade, a, 2_000_000, 4, 0)).isTrue();
        assertThat(tradeSystem.offerMeso(trade, b, 1000)).isTrue();

        CompletableFuture<TradeSystem.ConfirmResult> first = CompletableFuture.supplyAsync(
                () -> tradeSystem.confirm(trade, a));
        CompletableFuture<TradeSystem.ConfirmResult> second = CompletableFuture.supplyAsync(
                () -> tradeSystem.confirm(trade, b));
        Set<TradeSystem.ConfirmResult> results = Set.of(first.join(), second.join());

        assertThat(results).containsExactlyInAnyOrder(
                TradeSystem.ConfirmResult.WAITING, TradeSystem.ConfirmResult.COMPLETED);
        assertThat(a.getMeso()).isEqualTo(6000);
        assertThat(b.getMeso()).isEqualTo(4000);
        assertThat(a.getItemCount(2_000_000)).isEqualTo(6);
        assertThat(b.getItemCount(2_000_000)).isEqualTo(4);
        assertThat(trade.getState()).isEqualTo(Trade.State.DONE);
    }

    @Test
    @DisplayName("接收方剩余槽位不足时整笔拒绝且不扣除任何物品")
    void insufficientCombinedCapacityDoesNotPartiallySettle() {
        Character a = player(0);
        Character b = player(0);
        give(a, 2_000_000, 5);
        give(a, 2_000_001, 5);
        for (int index = 0; index < 23; index++) {
            give(b, 2_010_000 + index, 100);
        }

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(offer(trade, a, 2_000_000, 5, 0)).isTrue();
        assertThat(offer(trade, a, 2_000_001, 5, 1)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.lock(trade, b)).isTrue();

        assertThat(tradeSystem.complete(trade)).isFalse();
        assertThat(a.getItemCount(2_000_000)).isEqualTo(5);
        assertThat(a.getItemCount(2_000_001)).isEqualTo(5);
        assertThat(b.getItemCount(2_000_000)).isZero();
        assertThat(b.getItemCount(2_000_001)).isZero();
        assertThat(trade.getState()).isEqualTo(Trade.State.CANCELLED);
    }

    @Test
    @DisplayName("装备交易保留唯一 ID、所有者、期限及全部强化属性")
    void equipTransferPreservesExactInstance() {
        Character a = player(0);
        Character b = player(0);
        Equip source = new Equip(1_302_000);
        source.setCashId(7788);
        source.setOwner("Alice");
        source.setFlag(3);
        source.setExpiration(1_900_000_000_000L);
        source.setGiftFrom("Bob");
        source.setUpgradeSlots((byte) 6);
        source.setLevel((short) 4);
        source.setStr((short) 12);
        source.setWatk((short) 99);
        source.setItemLevel((byte) 5);
        source.setItemExp(12_345L);
        source.setRingId(456);
        assertThat(a.getInventory(InventoryType.EQUIP).addItem(source)).isTrue();

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(tradeSystem.offer(trade, a, InventoryType.EQUIP.getType(),
                source.getPosition(), 1, (byte) 0)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.lock(trade, b)).isTrue();
        assertThat(tradeSystem.complete(trade)).isTrue();

        assertThat(a.getInventory(InventoryType.EQUIP).items()).isEmpty();
        Item received = b.getInventory(InventoryType.EQUIP).getItem((short) 1);
        assertThat(received).isInstanceOf(Equip.class);
        Equip equip = (Equip) received;
        assertThat(equip.getCashId()).isEqualTo(7788);
        assertThat(equip.getOwner()).isEqualTo("Alice");
        assertThat(equip.getFlag()).isEqualTo(3);
        assertThat(equip.getExpiration()).isEqualTo(1_900_000_000_000L);
        assertThat(equip.getGiftFrom()).isEqualTo("Bob");
        assertThat(equip.getUpgradeSlots()).isEqualTo((byte) 6);
        assertThat(equip.getLevel()).isEqualTo((short) 4);
        assertThat(equip.getStr()).isEqualTo((short) 12);
        assertThat(equip.getWatk()).isEqualTo((short) 99);
        assertThat(equip.getItemLevel()).isEqualTo((byte) 5);
        assertThat(equip.getItemExp()).isEqualTo(12_345L);
        assertThat(equip.getRingId()).isEqualTo(456);
    }

    @Test
    @DisplayName("出价后原槽物品属性变化时整笔拒绝且不误扣同 ID 物品")
    void changedOfferedInstanceRejectsSettlement() {
        Character a = player(0);
        Character b = player(0);
        Item offered = new Item(2_000_000);
        offered.setQuantity((short) 5);
        offered.setOwner("before");
        assertThat(a.getInventory(InventoryType.USE).addItem(offered)).isTrue();

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(tradeSystem.offer(trade, a, InventoryType.USE.getType(),
                offered.getPosition(), 5, (byte) 0)).isTrue();
        offered.setOwner("after");
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.lock(trade, b)).isTrue();

        assertThat(tradeSystem.complete(trade)).isFalse();
        assertThat(a.getInventory(InventoryType.USE).getItem((short) 1)).isSameAs(offered);
        assertThat(b.getItemCount(2_000_000)).isZero();
        assertThat(trade.getState()).isEqualTo(Trade.State.CANCELLED);
    }

    @Test
    @DisplayName("双方背包已满时可利用各自移出的槽位互换物品")
    void outgoingItemsFreeSlotsForIncomingExchange() {
        Character a = player(0);
        Character b = player(0);
        for (int index = 0; index < 24; index++) {
            give(a, 2_010_000 + index, 1);
            give(b, 2_020_000 + index, 1);
        }
        Item fromA = a.getInventory(InventoryType.USE).getItem((short) 1);
        Item fromB = b.getInventory(InventoryType.USE).getItem((short) 1);
        assertThat(a.getInventory(InventoryType.USE).freeSlots()).isZero();
        assertThat(b.getInventory(InventoryType.USE).freeSlots()).isZero();

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(tradeSystem.offer(trade, a, InventoryType.USE.getType(),
                fromA.getPosition(), 1, (byte) 0)).isTrue();
        assertThat(tradeSystem.offer(trade, b, InventoryType.USE.getType(),
                fromB.getPosition(), 1, (byte) 0)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.lock(trade, b)).isTrue();

        assertThat(tradeSystem.complete(trade)).isTrue();
        assertThat(a.getItemCount(fromA.getId())).isZero();
        assertThat(a.getItemCount(fromB.getId())).isEqualTo(1);
        assertThat(b.getItemCount(fromB.getId())).isZero();
        assertThat(b.getItemCount(fromA.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("锁定后金币减少时结算失败且双方余额不变")
    void staleMesoOfferDoesNotPartiallySettle() {
        Character a = player(1000);
        Character b = player(500);
        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        assertThat(tradeSystem.offerMeso(trade, a, 800)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(tradeSystem.lock(trade, b)).isTrue();
        a.setMeso(100);

        assertThat(tradeSystem.complete(trade)).isFalse();
        assertThat(a.getMeso()).isEqualTo(100);
        assertThat(b.getMeso()).isEqualTo(500);
        assertThat(trade.getState()).isEqualTo(Trade.State.CANCELLED);
    }

    @Test
    @DisplayName("锁定后拒绝继续出价")
    void offerRejectedAfterLock() {
        Character a = player(0);
        give(a, 2_000_000, 10);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(player(0)));
        assertThat(offer(trade, a, 2_000_000, 5, 0)).isTrue();
        assertThat(tradeSystem.lock(trade, a)).isTrue();
        assertThat(offer(trade, a, 2_000_000, 3, 1)).isFalse();
    }

    @Test
    @DisplayName("一方未锁定则不能结算")
    void completeRejectedUntilBothLock() {
        Character a = player(0);
        give(a, 2_000_000, 10);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(player(0)));
        assertThat(offer(trade, a, 2_000_000, 5, 0)).isTrue();
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
        assertThat(offer(trade, a, 2_000_000, 6, 0)).isFalse();
    }

    @Test
    @DisplayName("版本门拒绝换代后的迟到出价")
    void versionGateBlocksStaleOffer() {
        Character a = new Character(versionGate.currentVersion());
        give(a, 2_000_000, 10);

        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(player(0)));
        versionGate.onReload();
        assertThat(offer(trade, a, 2_000_000, 5, 0)).isFalse();
    }

    // ---- 架构 5.3：显式中断 + 回滚（可感知极限 = 交易被取消） ----

    @Test
    @DisplayName("中断 ACTIVE 交易：出价清空、meso 归零、状态 CANCELLED、背包不动")
    void interrupt_activeTrade_clearsOffersAndCancels() {
        Character a = player(1000);
        Character b = player(1000);
        give(a, 2_000_000, 10);
        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        offer(trade, a, 2_000_000, 5, 0);
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
