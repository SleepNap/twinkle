package org.gms.hotreload;

import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-3 验收：按实体渐进重载机制（架构 5.3）。
 *
 * <p>验证：
 * <ul>
 *   <li>安全点跟踪：begin/end 后 isSafe 正确；未跟踪实体天然安全（丢物等单操作）。</li>
 *   <li>按实体渐进重载：安全点直切、在途显式中断（交易取消+回滚）、换代版本门。</li>
 *   <li>换代后旧逻辑迟到写被版本门拒（防复制 bug）——重载后旧版本写 STALE。</li>
 * </ul>
 */
class EntityReloadServiceTest {

    @Test
    void coordinatorTracksInFlightAndSafePoints() {
        EntityReloadCoordinator c = new EntityReloadCoordinator();

        // 未跟踪实体天然安全（丢物等单操作）
        assertThat(c.isSafe(1L)).isTrue();

        // 交易等长操作：进入在途
        assertThat(c.beginOperation(1L)).isTrue();
        assertThat(c.inOperation(1L)).isTrue();
        assertThat(c.isSafe(1L)).isFalse();
        assertThat(c.inFlightCount()).isEqualTo(1);

        // 结束回到安全点
        assertThat(c.endOperation(1L)).isTrue();
        assertThat(c.isSafe(1L)).isTrue();
        assertThat(c.inFlightCount()).isZero();
    }

    @Test
    void nestedOperationNeedsMultipleEnds() {
        EntityReloadCoordinator c = new EntityReloadCoordinator();

        c.beginOperation(5L);
        assertThat(c.beginOperation(5L)).isFalse();  // 嵌套进入
        assertThat(c.inOperation(5L)).isTrue();
        assertThat(c.endOperation(5L)).isFalse();    // 仍有外层在途
        assertThat(c.endOperation(5L)).isTrue();     // 全部结束回安全点
        assertThat(c.isSafe(5L)).isTrue();
    }

    @Test
    void reloadSwitchesSafeDirectlyInterruptsInFlightAndAdvancesVersion() {
        EntityReloadCoordinator c = new EntityReloadCoordinator();
        VersionGate gate = new DefaultVersionGate();
        EntityReloadService service = new EntityReloadService(c, gate);

        long oldVersion = gate.currentVersion();
        // 实体 1 在安全点（丢物等单操作）；实体 2 在交易中
        AtomicInteger interrupts = new AtomicInteger();
        c.beginOperation(2L);

        EntityReloadService.ReloadResult result = service.reload(
                List.of(1L, 2L),
                id -> { interrupts.incrementAndGet(); c.endOperation(id); return true; });

        assertThat(result.safeSwitched()).isEqualTo(1);       // 实体 1 安全直切
        assertThat(result.interrupted()).isEqualTo(1);        // 实体 2 显式中断
        assertThat(result.newVersion()).isEqualTo(oldVersion + 1);
        assertThat(gate.currentVersion()).isEqualTo(oldVersion + 1);
        assertThat(c.isSafe(2L)).isTrue();                    // 中断后回安全点
    }

    @Test
    void unInterruptibleEntityIsSkippedButVersionStillAdvances() {
        EntityReloadCoordinator c = new EntityReloadCoordinator();
        VersionGate gate = new DefaultVersionGate();
        EntityReloadService service = new EntityReloadService(c, gate);

        c.beginOperation(9L);
        EntityReloadService.ReloadResult result = service.reload(
                List.of(9L),
                id -> false);   // 中断失败 → 跳过（等待自然结束）

        assertThat(result.interrupted()).isZero();
        assertThat(result.safeSwitched()).isZero();
        assertThat(result.newVersion()).isEqualTo(gate.currentVersion());
    }

    @Test
    void staleWritesAfterReloadAreRejectedByVersionGate() {
        // 防复制 bug 核心：换代后旧逻辑迟到写 → STALE
        VersionGate gate = new DefaultVersionGate();
        long oldVersion = gate.currentVersion();
        gate.onReload();

        assertThat(gate.decide(oldVersion))
                .isEqualTo(org.gms.hotreload.versioned.VersionDecision.STALE);
    }

    @Test
    void safeOnlyFiltersInFlight() {
        EntityReloadCoordinator c = new EntityReloadCoordinator();
        c.beginOperation(2L);
        c.beginOperation(3L);

        List<Long> safe = c.safeOnly(List.of(1L, 2L, 3L, 4L));

        assertThat(safe).containsExactly(1L, 4L);   // 只有安全点的实体可切换
    }
}
