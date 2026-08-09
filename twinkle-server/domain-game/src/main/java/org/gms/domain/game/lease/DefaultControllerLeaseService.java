package org.gms.domain.game.lease;

import lombok.extern.log4j.Log4j2;
import org.gms.tick.TickHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 怪物控制租约实现（事故报告阶段 B / §5.5，稳定层，不随可重载 handler 换代）。
 *
 * <p>归属模型：
 * <ul>
 *   <li>每怪归属 {@code Map<MonsterKey(mapId,oid), LeaseOwner>}——控制权归属身份。</li>
 *   <li>每 owner 状态 {@code Map<LeaseOwner, OwnerLease>}——受控活怪集合 + 最后有效
 *       MOVE_LIFE + 到期 + 冷却。受控活怪数<b>按 owner 聚合</b>（报告 §4.4：按 Client
 *       计算，不每怪各算超时）。</li>
 *   <li>当前代际投影 {@code Map<Long characterId, CurrentClaim(sessionId,generation)>}——
 *       判"该 owner 是否仍有效"。</li>
 * </ul>
 *
 * <p>规则（严格对照报告 §4.2/§4.3/§5.5）：
 * <ul>
 *   <li>{@link #renew} fail-closed：任一校验不过返回 false，调用方丢弃整包。</li>
 *   <li>怪物死亡/消失/换图/代际替换从 owner 受控集合移除；<b>计数归零→IDLE，清时间戳，
 *       不进冷却、不累计、不警告</b>；再次分到第一只怪从全新宽限期起。</li>
 *   <li><b>仅 {@link LeaseReleaseReason#LEASE_EXPIRED} 进冷却</b>；冷却按 owner（新登录
 *       新代际即清零）。</li>
 *   <li>{@link #onClaim} 写投影后立即以 SESSION_REPLACED 释放旧代际全部租约；
 *       {@link #sweep} 做防御性代际回收。</li>
 * </ul>
 *
 * <p>巡检：实现 {@link TickHandler} 挂既有游戏 tick（2C2G 不新增线程）。tick 暂停
 * （热重载 DRAINING/停服）时 handler 不被调用→不扫不误释放；恢复后检测 gap 超过
 * 3×周期即为暂停，对活跃 owner 统一加宽限（报告 §5.3-5）。
 */
@Log4j2
public final class DefaultControllerLeaseService implements ControllerLeaseService, TickHandler {



    /** 怪物定位键（mapId, oid）。 */
    private record MonsterKey(int mapId, int monsterOid) {
    }

    /** 当前代际投影（角色 → 认领它的连接身份）。 */
    private record CurrentClaim(long sessionId, long generation) {
    }

    /** 每 owner 租约状态（受控怪集合 + 期限；冷却独立存 cooldowns，不随 owner 清除丢失）。 */
    private static final class OwnerLease {
        private final Set<MonsterKey> monsters = ConcurrentHashMap.newKeySet();
        private long lastRenewAtNanos;
        private long expiryAtNanos;
    }

    /** 每怪归属。 */
    private final ConcurrentMap<MonsterKey, LeaseOwner> monsterOwner = new ConcurrentHashMap<>();
    /** 每 owner 状态。 */
    private final ConcurrentMap<LeaseOwner, OwnerLease> owners = new ConcurrentHashMap<>();
    /** 当前代际投影。 */
    private final ConcurrentMap<Long, CurrentClaim> currentClaims = new ConcurrentHashMap<>();
    /** 控制权短冷却（characterId → 冷却到期时刻；仅 LEASE_EXPIRED 进入，独立于 owner 存续）。 */
    private final ConcurrentMap<Long, Long> cooldowns = new ConcurrentHashMap<>();

    /** 租约到期时长（TTL）。 */
    private final long ttlNanos;
    /** 控制权释放短冷却。 */
    private final long cooldownNanos;
    /** 巡检间隔（毫秒，构造注入：tick 间隔 × sweepTicks）。 */
    private final long sweepIntervalMillis;
    /** 单调时钟（默认 {@link System#nanoTime()}；测试注入假时钟，报告 §5.3-6）。 */
    private final LongSupplier clock;

    private volatile long lastSweepAtNanos;
    private final AtomicLong tickCounter = new AtomicLong();

    // ---- 观测计数（报告 §七） ----
    private final AtomicLong releaseExpired = new AtomicLong();
    private final AtomicLong releaseMonsterDied = new AtomicLong();
    private final AtomicLong releaseDespawned = new AtomicLong();
    private final AtomicLong releaseMapLeft = new AtomicLong();
    private final AtomicLong releaseSessionReplaced = new AtomicLong();
    private final AtomicLong renewRejectedNotOwner = new AtomicLong();
    private final AtomicLong renewRejectedGeneration = new AtomicLong();
    private final AtomicLong claimRejectedCooldown = new AtomicLong();
    private final AtomicLong supersededOwnerReclaimed = new AtomicLong();

    /**
     * @param ttlSeconds          租约到期秒数（最后有效 MOVE_LIFE 距今超过即释放）
     * @param cooldownSeconds     控制权释放短冷却秒数（仅 LEASE_EXPIRED 进入）
     * @param sweepIntervalMillis 巡检间隔毫秒（= tick 间隔 × 每 N tick 扫一次）
     */
    public DefaultControllerLeaseService(long ttlSeconds, long cooldownSeconds, long sweepIntervalMillis) {
        this(ttlSeconds, cooldownSeconds, sweepIntervalMillis, System::nanoTime);
    }

    /** 测试注入假时钟（单调单调递增即可）。 */
    DefaultControllerLeaseService(long ttlSeconds, long cooldownSeconds, long sweepIntervalMillis,
                                  LongSupplier clock) {
        this.ttlNanos = ttlSeconds * 1_000_000_000L;
        this.cooldownNanos = cooldownSeconds * 1_000_000_000L;
        this.sweepIntervalMillis = sweepIntervalMillis;
        this.clock = clock;
        this.lastSweepAtNanos = clock.getAsLong();
    }

    private long now() {
        return clock.getAsLong();
    }

    @Override
    public synchronized void onClaim(long characterId, long sessionId, long generation) {
        currentClaims.put(characterId, new CurrentClaim(sessionId, generation));
        // 新代际登录 = 全新开始：清冷却（防御"坏客户端重登立刻再拿怪"可接受，报告 §4.5）
        cooldowns.remove(characterId);
        // 旧代际（同 characterId 其他 sessionId/generation）的全部租约立即失效
        for (LeaseOwner owner : owners.keySet()) {
            if (owner.characterId() == characterId
                    && (owner.sessionId() != sessionId || owner.generation() != generation)) {
                releaseAllForOwner(owner, LeaseReleaseReason.SESSION_REPLACED);
                supersededOwnerReclaimed.incrementAndGet();
            }
        }
    }

    @Override
    public synchronized void onDisconnect(long characterId, long sessionId, long generation) {
        CurrentClaim claim = currentClaims.get(characterId);
        if (claim == null || claim.sessionId() != sessionId || claim.generation() != generation) {
            return; // 旧代际迟到关闭：no-op（报告 §5.4）
        }
        currentClaims.remove(characterId, claim);
        releaseAllForOwner(new LeaseOwner(characterId, sessionId, generation), LeaseReleaseReason.MAP_LEFT);
    }

    @Override
    public synchronized boolean tryClaim(int mapId, int monsterOid, LeaseOwner owner) {
        MonsterKey key = new MonsterKey(mapId, monsterOid);
        LeaseOwner current = monsterOwner.get(key);
        if (current != null && isCurrentOwner(current)) {
            return false; // 已有有效控制者
        }
        if (isInCooldown(owner.characterId())) {
            claimRejectedCooldown.incrementAndGet();
            return false;
        }
        // 接管：原 owner（陈旧代际）从受控集合移除；owner 从 0 只切到 1 时开全新宽限
        if (current != null) {
            OwnerLease prev = owners.get(current);
            if (prev != null) {
                prev.monsters.remove(key);
                if (prev.monsters.isEmpty()) {
                    owners.remove(current, prev);
                }
            }
        }
        monsterOwner.put(key, owner);
        OwnerLease lease = owners.computeIfAbsent(owner, o -> new OwnerLease());
        boolean firstMonster = lease.monsters.isEmpty();
        lease.monsters.add(key);
        if (firstMonster) {
            // 新 GRACE：旧时间戳不得沿用（报告 §4.3）
            long now = now();
            lease.lastRenewAtNanos = now;
            lease.expiryAtNanos = now + ttlNanos;
        }
        return true;
    }

    @Override
    public synchronized boolean renew(int mapId, int monsterOid, LeaseOwner owner) {
        MonsterKey key = new MonsterKey(mapId, monsterOid);
        LeaseOwner current = monsterOwner.get(key);
        if (current == null || !current.equals(owner)) {
            renewRejectedNotOwner.incrementAndGet();
            return false; // 越权/伪造 MOVE_LIFE
        }
        CurrentClaim claim = currentClaims.get(owner.characterId());
        if (claim == null || claim.sessionId() != owner.sessionId() || claim.generation() != owner.generation()) {
            renewRejectedGeneration.incrementAndGet();
            return false; // 旧代际续租（迟到/被替换后）
        }
        OwnerLease lease = owners.get(owner);
        if (lease == null || !lease.monsters.contains(key)) {
            renewRejectedNotOwner.incrementAndGet();
            return false;
        }
        // 任一受控活怪的有效包续租整个 owner（报告 §4.2）
        long now = now();
        lease.lastRenewAtNanos = now;
        lease.expiryAtNanos = now + ttlNanos;
        return true;
    }

    @Override
    public synchronized void release(int mapId, int monsterOid, LeaseReleaseReason reason) {
        MonsterKey key = new MonsterKey(mapId, monsterOid);
        LeaseOwner owner = monsterOwner.remove(key);
        if (owner == null) {
            return;
        }
        OwnerLease lease = owners.get(owner);
        if (lease != null) {
            lease.monsters.remove(key);
            if (lease.monsters.isEmpty()) {
                owners.remove(owner, lease);
            }
        }
        // 自然归零 → IDLE（报告 §4.3）：不进入冷却（仅 LEASE_EXPIRED 冷却）、不累计、不警告
        count(reason).incrementAndGet();
    }

    @Override
    public synchronized boolean isUnowned(int mapId, int monsterOid) {
        LeaseOwner owner = monsterOwner.get(new MonsterKey(mapId, monsterOid));
        return owner == null || !isCurrentOwner(owner);
    }

    @Override
    public synchronized boolean isInCooldown(long characterId) {
        Long until = cooldowns.get(characterId);
        return until != null && now() < until;
    }

    @Override
    public synchronized int controlledAliveCount(LeaseOwner owner) {
        OwnerLease lease = owners.get(owner);
        return lease == null ? 0 : lease.monsters.size();
    }

    @Override
    public synchronized void sweep(long nowNanos) {
        // tick 暂停宽限：gap 超过 3× 周期说明期间 tick 停过（热重载 DRAINING/停服），
        // 恢复后对活跃 owner 统一加宽限，避免批量误释放（报告 §5.3-5）
        if (lastSweepAtNanos > 0) {
            long gap = nowNanos - lastSweepAtNanos;
            long expected = sweepIntervalMillis * 1_000_000L;
            if (gap > expected * 3) {
                long grace = gap - expected;
                for (OwnerLease lease : owners.values()) {
                    if (!lease.monsters.isEmpty()) {
                        lease.expiryAtNanos += grace;
                    }
                }
                log.info("检测到 tick 暂停（{}ms），对 {} 个活跃 owner 加宽限 {}ms",
                        gap / 1_000_000L, owners.size(), grace / 1_000_000L);
            }
        }
        lastSweepAtNanos = nowNanos;

        // 过期释放
        for (LeaseOwner owner : owners.keySet()) {
            OwnerLease lease = owners.get(owner);
            if (lease == null || lease.monsters.isEmpty()) {
                continue;
            }
            if (nowNanos >= lease.expiryAtNanos) {
                // 仅主动超时释放进入冷却（报告 §4.3）
                cooldowns.put(owner.characterId(), nowNanos + cooldownNanos);
                releaseAllForOwner(owner, LeaseReleaseReason.LEASE_EXPIRED);
            } else if (!isCurrentOwner(owner)) {
                // 防御性代际回收（onClaim 之外的安全网）
                releaseAllForOwner(owner, LeaseReleaseReason.SESSION_REPLACED);
                supersededOwnerReclaimed.incrementAndGet();
            }
        }
    }

    @Override
    public synchronized LeaseStats stats() {
        int controlled = owners.values().stream().mapToInt(o -> o.monsters.size()).sum();
        return new LeaseStats(controlled,
                releaseExpired.get(), releaseMonsterDied.get(), releaseDespawned.get(),
                releaseMapLeft.get(), releaseSessionReplaced.get(),
                renewRejectedNotOwner.get(), renewRejectedGeneration.get(),
                claimRejectedCooldown.get(), supersededOwnerReclaimed.get());
    }

    // ---- TickHandler（挂既有游戏 tick，不新增线程） ----

    @Override
    public void tick(long tickCount) {
        long count = tickCounter.incrementAndGet();
        if (count % sweepTicks() == 0) {
            sweep(now());
        }
    }

    /** 每 N tick 扫一次（sweepIntervalMillis / tick 间隔换算；100ms tick → 100 ticks = 10s）。 */
    private long sweepTicks() {
        return Math.max(1, sweepIntervalMillis / 100L);
    }

    // ---- 内部 ----

    private boolean isCurrentOwner(LeaseOwner owner) {
        CurrentClaim claim = currentClaims.get(owner.characterId());
        return claim != null && claim.sessionId() == owner.sessionId() && claim.generation() == owner.generation();
    }

    private void releaseAllForOwner(LeaseOwner owner, LeaseReleaseReason reason) {
        OwnerLease lease = owners.get(owner);
        if (lease == null) {
            return;
        }
        for (MonsterKey key : lease.monsters) {
            monsterOwner.remove(key, owner);
        }
        lease.monsters.clear();
        owners.remove(owner, lease);
        count(reason).incrementAndGet();
    }

    private AtomicLong count(LeaseReleaseReason reason) {
        return switch (reason) {
            case LEASE_EXPIRED -> releaseExpired;
            case MONSTER_DIED -> releaseMonsterDied;
            case DESPAWNED -> releaseDespawned;
            case MAP_LEFT -> releaseMapLeft;
            case SESSION_REPLACED -> releaseSessionReplaced;
        };
    }
}
