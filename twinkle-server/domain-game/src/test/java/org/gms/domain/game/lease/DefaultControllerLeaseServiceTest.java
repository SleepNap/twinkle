package org.gms.domain.game.lease;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 怪物控制租约单元测试（事故报告 §七 完成标准 2/3/5 + §4.3 自然归零规则）。
 *
 * <p>注入假单调时钟（AtomicLong，测试内手动推进），tryClaim/renew/sweep 看到的
 * 时间一致；sweep 经 {@code tick} 或直接调用驱动，不依赖真实 tick 线程。
 */
class DefaultControllerLeaseServiceTest {

    private final AtomicLong clock = new AtomicLong(0);

    private final DefaultControllerLeaseService service = newService();

    private DefaultControllerLeaseService newService() {
        return new DefaultControllerLeaseService(50, 15, 10_000, 100, clock::get);
    }

    private final LeaseOwner alice = new LeaseOwner(1001, 9001, 1);
    private final LeaseOwner aliceReplacement = new LeaseOwner(1001, 9002, 2);

    private void advanceSeconds(long s) {
        clock.addAndGet(s * 1_000_000_000L);
    }

    @Test
    void claimAndRenewKeepsLease() {
        service.onClaim(1001, 9001, 1);
        assertThat(service.tryClaim(10, 1, alice)).isTrue();
        assertThat(service.controlledAliveCount(alice)).isEqualTo(1);

        assertThat(service.renew(10, 1, alice)).isTrue();
        advanceSeconds(30);                       // t=30s，未到 TTL 50s
        service.sweep(clock.get());
        assertThat(service.controlledAliveCount(alice)).isEqualTo(1);
        assertThat(service.isUnowned(10, 1)).isFalse();
    }

    @Test
    void noMoveLifeExpiresAndEntersCooldown() {
        service.onClaim(1001, 9001, 1);
        service.tryClaim(10, 1, alice);
        advanceSeconds(60);                       // 不发 MOVE_LIFE：t=60s > TTL 50s
        service.sweep(clock.get());
        assertThat(service.controlledAliveCount(alice)).isZero();
        assertThat(service.isUnowned(10, 1)).isTrue();
        assertThat(service.isInCooldown(1001)).isTrue();
        assertThat(service.tryClaim(10, 1, alice)).isFalse();   // 冷却中不能接管
        assertThat(service.stats().claimRejectedCooldown()).isEqualTo(1);
        assertThat(service.stats().releaseExpired()).isEqualTo(1);
    }

    @Test
    void naturalZeroDoesNotCooldownAndGetsFreshGrace() {
        service.onClaim(1001, 9001, 1);
        service.tryClaim(10, 1, alice);
        service.release(10, 1, LeaseReleaseReason.MONSTER_DIED); // 唯一受控怪被别人击杀
        assertThat(service.controlledAliveCount(alice)).isZero();
        assertThat(service.isInCooldown(1001)).isFalse();        // 自然归零不进冷却

        advanceSeconds(200);                                     // IDLE 很久后
        assertThat(service.tryClaim(10, 2, alice)).isTrue();     // 重新分到怪 → 全新宽限期
        service.sweep(clock.get());
        assertThat(service.controlledAliveCount(alice)).isEqualTo(1); // 未因旧时间戳被误判过期
        assertThat(service.stats().releaseMonsterDied()).isEqualTo(1);
    }

    @Test
    void staleGenerationRenewRejectedAndSessionReplaced() {
        service.onClaim(1001, 9001, 1);
        service.tryClaim(10, 1, alice);
        // 标准 5/3：新连接 B 认领同角色 gen2 → 旧代际租约立即失效（SESSION_REPLACED）
        service.onClaim(1001, 9002, 2);
        assertThat(service.controlledAliveCount(alice)).isZero();
        assertThat(service.isUnowned(10, 1)).isTrue();
        // 旧代际（9001/1）迟到续租 → fail-closed 被拒（怪物已随 SESSION_REPLACED 释放，不再归它）
        assertThat(service.renew(10, 1, alice)).isFalse();
        assertThat(service.stats().renewRejectedNotOwner()).isEqualTo(1);
        // 新代际可接管
        assertThat(service.tryClaim(10, 1, aliceReplacement)).isTrue();
        // 旧连接迟到 onDisconnect → no-op，新代际租约不受影响
        service.onDisconnect(1001, 9001, 1);
        assertThat(service.controlledAliveCount(aliceReplacement)).isEqualTo(1);
        assertThat(service.stats().releaseSessionReplaced()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void currentGenerationDisconnectReleasesWithMapLeft() {
        service.onClaim(1001, 9001, 1);
        service.tryClaim(10, 1, alice);
        service.onDisconnect(1001, 9001, 1);
        assertThat(service.controlledAliveCount(alice)).isZero();
        assertThat(service.isUnowned(10, 1)).isTrue();
        assertThat(service.stats().releaseMapLeft()).isEqualTo(1);
        assertThat(service.isInCooldown(1001)).isFalse();        // MAP_LEFT 不进冷却
    }

    @Test
    void failedRenewWhenNotOwnerIsRejected() {
        service.onClaim(1001, 9001, 1);
        service.onClaim(2002, 8001, 1);
        service.tryClaim(10, 1, alice);
        LeaseOwner bob = new LeaseOwner(2002, 8001, 1);
        assertThat(service.renew(10, 1, bob)).isFalse();         // B 伪造控制 A 的怪
        assertThat(service.stats().renewRejectedNotOwner()).isEqualTo(1);
        assertThat(service.renew(10, 1, alice)).isTrue();        // A 自己续租仍有效
    }

    @Test
    void tickPauseGrantsGraceAndDoesNotMisfire() {
        // 模拟热重载暂停：tick 停了一段（gap > 3×周期），恢复后 sweep
        service.onClaim(1001, 9001, 1);
        service.tryClaim(10, 1, alice);
        service.sweep(clock.get());               // 建立 lastSweepAt = t=0
        advanceSeconds(40);                       // 停了 40s（> 30s 阈值），期间无 sweep
        service.sweep(clock.get());               // 恢复：gap=40s → 对活跃 owner 加宽限 30s
        assertThat(service.controlledAliveCount(alice)).isEqualTo(1); // 未因暂停被误过期
        assertThat(service.renew(10, 1, alice)).isTrue();
    }

    @Test
    void releaseReasonCountsTracked() {
        service.onClaim(1001, 9001, 1);
        service.tryClaim(10, 1, alice);
        service.release(10, 1, LeaseReleaseReason.DESPAWNED);
        assertThat(service.stats().releaseDespawned()).isEqualTo(1);
    }

    @Test
    void despawnedReleaseAlsoZeroesWithoutCooldown() {
        service.onClaim(1001, 9001, 1);
        service.tryClaim(10, 1, alice);
        service.release(10, 1, LeaseReleaseReason.DESPAWNED);
        assertThat(service.controlledAliveCount(alice)).isZero();
        assertThat(service.isInCooldown(1001)).isFalse();
        // 重新分怪：完整新宽限
        assertThat(service.tryClaim(10, 1, alice)).isTrue();
    }

    @Test
    void tickSweepUsesConfiguredBaseInterval() {
        DefaultControllerLeaseService configured =
                new DefaultControllerLeaseService(50, 15, 1_000, 250, clock::get);
        configured.onClaim(1001, 9001, 1);
        configured.tryClaim(10, 1, alice);
        advanceSeconds(60);

        configured.tick(1);
        configured.tick(2);
        configured.tick(3);
        assertThat(configured.controlledAliveCount(alice)).isEqualTo(1);

        configured.tick(4);
        assertThat(configured.controlledAliveCount(alice)).isZero();
    }
}
