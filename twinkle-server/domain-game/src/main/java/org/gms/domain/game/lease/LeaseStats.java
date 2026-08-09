package org.gms.domain.game.lease;

/**
 * 怪物控制租约观测快照（事故报告 §七：受控活怪数、释放原因、续租拒绝计数）。
 *
 * <p>不可变值对象；沿用 {@code MonsterSpawnService.stats()} 自带快照先例（可观测口子，
 * 不硬接 Metrics，做管理端时直接可读）。
 */
public record LeaseStats(
        int controlledMonsters,
        long releaseExpired,
        long releaseMonsterDied,
        long releaseDespawned,
        long releaseMapLeft,
        long releaseSessionReplaced,
        long renewRejectedNotOwner,
        long renewRejectedGeneration,
        long claimRejectedCooldown,
        long supersededOwnerReclaimed) {
}
