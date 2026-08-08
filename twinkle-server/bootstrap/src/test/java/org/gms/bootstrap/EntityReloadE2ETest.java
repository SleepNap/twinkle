package org.gms.bootstrap;

import org.gms.hotreload.EntityReloadCoordinator;
import org.gms.hotreload.EntityReloadService;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;
import org.gms.replaceable.ItemSystem;
import org.gms.replaceable.TradeSystem;
import org.gms.domain.game.Character;
import org.gms.domain.game.item.ItemData;
import org.gms.domain.game.trade.Trade;
import org.gms.domain.game.trade.TradeSide;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-3 验收：重载过程中并发交易/丢物，无复制 bug、无丢锁（架构 5.3 / M3-3 验收标准）。
 *
 * <p>集成场景：
 * <ol>
 *   <li>玩家 A 正在交易（长操作在途，实体被 EntityReloadCoordinator 跟踪）。</li>
 *   <li>触发按实体渐进重载 → 交易被显式中断（CANCELLED + 背包归位），实体回安全点，
 *       版本门换代。</li>
 *   <li>换代后旧逻辑迟到写（丢物/交易出价）被版本门拒 → 无复制 bug、无丢锁。</li>
 * </ol>
 */
class EntityReloadE2ETest {

    @Test
    void reloadInterruptsActiveTradeWithoutDupItems() {
        // ---- 装配（与 GamePlayE2ETest 一致的手动装配）----
        VersionGate gate = new DefaultVersionGate();
        ItemData potion = new ItemData(2_000_000);
        potion.setSlotMax(100);
        Map<Integer, ItemData> itemData = Map.of(2_000_000, potion);
        ItemSystem itemSystem = new ItemSystem(gate, itemData);
        TradeSystem tradeSystem = new TradeSystem(gate, itemSystem);
        EntityReloadCoordinator coordinator = new EntityReloadCoordinator();
        EntityReloadService reloadService = new EntityReloadService(coordinator, gate);

        Character a = new Character(gate.currentVersion());
        Character b = new Character(gate.currentVersion());
        a.setMeso(5000);
        b.setMeso(5000);
        assertThat(itemSystem.giveItem(a, 2_000_000, 10)).isTrue();

        // ---- 玩家 A 进入交易（长操作在途）----
        Trade trade = tradeSystem.create(new TradeSide(a), new TradeSide(b));
        tradeSystem.offer(trade, a, 2_000_000, 5);
        tradeSystem.offerMeso(trade, b, 1000);
        coordinator.beginOperation(a.getId());
        coordinator.beginOperation(b.getId());
        assertThat(coordinator.inOperation(a.getId())).isTrue();

        // ---- 触发按实体渐进重载：A/B 在途 → 显式中断交易 ----
        EntityReloadService.ReloadResult result = reloadService.reload(
                java.util.List.of(a.getId(), b.getId()),
                id -> {
                    // 中断：交易取消 + 回滚（PlayerInteractionHandler 的 endOperations 同语义）
                    // 只中断一次（中断后交易已 CANCELLED，第二个实体仅释放跟踪）
                    boolean interrupted = tradeSystem.interrupt(trade);
                    coordinator.endOperation(id);
                    return interrupted;
                });

        // 至少中断了交易（第一实体触发 CANCELLED；第二实体共享同一交易，操作已随之结束）
        assertThat(result.interrupted()).isGreaterThanOrEqualTo(1);
        assertThat(trade.getState()).isEqualTo(Trade.State.CANCELLED);
        assertThat(coordinator.isSafe(a.getId())).isTrue();
        assertThat(coordinator.isSafe(b.getId())).isTrue();

        // ---- 无复制 bug：物品从未离开背包 ----
        assertThat(itemSystem.countItem(a, 2_000_000)).isEqualTo(10);
        assertThat(a.getMeso()).isEqualTo(5000);
        assertThat(b.getMeso()).isEqualTo(5000);

        // ---- 版本门换代：旧逻辑迟到写被拒（丢物/出价都无效）----
        // 模拟旧逻辑（换代前版本）继续尝试操作
        assertThat(gate.decide(result.newVersion() - 1))
                .isEqualTo(VersionDecision.STALE);

        // 新逻辑可正常写（版本一致）
        Character c = new Character(gate.currentVersion());
        assertThat(itemSystem.giveItem(c, 2_000_000, 1)).isTrue();
    }

    @Test
    void idleEntityReloadsWithoutInterrupt() {
        // 丢物等单操作实体天然安全：无在途 → reload 直接切换，不打断
        VersionGate gate = new DefaultVersionGate();
        EntityReloadCoordinator coordinator = new EntityReloadCoordinator();
        EntityReloadService reloadService = new EntityReloadService(coordinator, gate);
        long oldVersion = gate.currentVersion();

        EntityReloadService.ReloadResult result = reloadService.reload(
                java.util.List.of(100L, 101L),
                id -> false);   // 无在途 → 不触发 interrupt

        assertThat(result.safeSwitched()).isEqualTo(2);
        assertThat(result.interrupted()).isZero();
        assertThat(result.newVersion()).isEqualTo(oldVersion + 1);
    }
}
