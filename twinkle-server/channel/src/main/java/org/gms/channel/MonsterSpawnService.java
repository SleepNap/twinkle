package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.lease.ControllerLeaseService;
import org.gms.domain.game.lease.LeaseOwner;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.SpawnPoint;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.domain.game.spi.CharacterState;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.PacketSession;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 怪物刷怪服务（M3-5：SpawnPoint → 实例化 → 重生调度；阶段 B：控制权分配）。
 *
 * <p>地图按 {@code SpawnPoint}（monsterId/x/y/respawnInterval/chance）+ {@code MobData}
 * 实例化 {@link MapleMonster}、分配 objectId、加入地图；玩家进图时把怪物广播给进入玩家。
 * 怪物死亡后按 respawnInterval 延时重生（低配独立单线程调度器，2C2G 预算可控）。
 *
 * <p><b>怪物控制权分配</b>（事故报告阶段 B）：每只怪最多一个控制者（发
 * {@code SPAWN_MONSTER_CONTROL}），其余玩家收普通 {@code SPAWN_MONSTER}。控制权经
 * {@link ControllerLeaseService}（稳定层租约）申请——只有仍在场、有有效会话、不在冷却的
 * 玩家才能接管；无主怪由 {@link #reassign} 周期分给在场玩家（租约超时释放后健康玩家接管）。
 *
 * <p><b>去重</b>（事故报告 §11 坑）：{@link #ensureSpawned} 按同 mobId 活怪去重，第二个
 * 玩家进图不会把怪物翻倍；{@link #onPlayerEnter} 只对进入玩家单独广播现存怪。
 *
 * <p><b>可观测口子</b>（用户要求：任务/流程/子线程可监控，勿做隐式）：自带原子计数
 * 与快照查询（{@link #stats()}），做 Web/M5 管理端时直接可读，不依赖未装配的
 * Metrics bean。重生调度器的生命周期/异常经 {@link #close} 与计数暴露。
 */
@Log4j2
public final class MonsterSpawnService {



    private final Map<Integer, MobData> mobData;
    private final PlayerSessionRegistry sessions;
    private final ControllerLeaseService leaseService;
    private final ScheduledExecutorService respawnScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "monster-respawn");
        t.setDaemon(true);
        return t;
    });

    /** 可观测计数（重生调度/生成成功/缺数据，做 Web 监控时读取）。 */
    private final AtomicLong respawnScheduled = new AtomicLong();
    private final AtomicLong respawnExecuted = new AtomicLong();
    private final AtomicLong spawned = new AtomicLong();
    private final AtomicLong missingMobData = new AtomicLong();
    private final AtomicLong skippedDuplicate = new AtomicLong();
    private final AtomicLong reassigned = new AtomicLong();

    public MonsterSpawnService(Map<Integer, MobData> mobData, PlayerSessionRegistry sessions,
                               ControllerLeaseService leaseService) {
        this.mobData = mobData;
        this.sessions = sessions;
        this.leaseService = leaseService;
    }

    /**
     * 生成缺失的怪物（首次进图/换图清除后补刷）。
     *
     * <p>按同 mobId 活怪去重：已存在则跳过（防第二个玩家进图把怪物翻倍）。
     * 只生成不广播——进图玩家的刷怪包由 {@link #onPlayerEnter} 单独发（避免双发）。
     */
    public void ensureSpawned(MapleMap map) {
        for (SpawnPoint sp : map.spawnPoints()) {
            if (hasAliveMonster(map, sp.getMonsterId())) {
                skippedDuplicate.incrementAndGet();
                continue;
            }
            spawnOne(map, sp);
        }
    }

    /**
     * 玩家进图：把地图现存怪单独广播给该玩家，无主怪尝试把控制权分给它。
     *
     * <p>这是进图玩家收到刷怪包的<b>唯一</b>通道：无主怪 tryClaim 成功发
     * {@code SPAWN_MONSTER_CONTROL}（0xEE），否则发普通 {@code SPAWN_MONSTER}（0xEC）。
     */
    public void onPlayerEnter(MapleMap map, PacketSession session, LeaseOwner owner) {
        for (MapleMonster monster : map.monsters()) {
            if (!monster.isAlive()) {
                continue;
            }
            boolean claimed = leaseService.tryClaim(map.getMapId(), monster.getObjectId(), owner);
            session.send(claimed
                    ? GamePacketFactory.spawnMonsterControl(monster)
                    : GamePacketFactory.spawnMonster(monster));
        }
    }

    /**
     * 无主怪重新分配（租约超时释放后，健康玩家已在场时的接管；周期巡检调用）。
     *
     * <p>只对无主活怪生效：挑在场第一个有有效会话且不在冷却的玩家接管，只给新 owner
     * 发 0xEE（不重发 0xEC，避免重复生成）。
     */
    public void reassign(MapleMap map) {
        if (map.characters().isEmpty()) {
            return;
        }
        for (MapleMonster monster : map.monsters()) {
            if (!monster.isAlive() || !leaseService.isUnowned(map.getMapId(), monster.getObjectId())) {
                continue;
            }
            LeaseOwner owner = pickController(map, monster);
            if (owner != null) {
                PacketSession s = sessions.get(owner.characterId());
                if (s != null) {
                    s.send(GamePacketFactory.spawnMonsterControl(monster));
                    reassigned.incrementAndGet();
                }
            }
        }
    }

    /** 生成一只怪物（未过概率/无数据返回 null）。 */
    private MapleMonster spawnOne(MapleMap map, SpawnPoint sp) {
        if (sp.getChance() > 0 && (sp.getChance() < 100 && Math.random() * 100 > sp.getChance())) {
            return null;
        }
        MobData data = mobData.get(sp.getMonsterId());
        if (data == null) {
            missingMobData.incrementAndGet();
            log.warn("刷怪缺 MobData: monsterId={}", sp.getMonsterId());
            return null;
        }
        MapleMonster monster = new MapleMonster(data);
        monster.setObjectId(map.nextObjectId());
        monster.setX(sp.getX());
        monster.setY(sp.getY());
        map.addMonster(monster);
        spawned.incrementAndGet();
        return monster;
    }

    /** 广播新怪：尝试分配控制者（0xEE 给 owner），其余玩家收 0xEC。 */
    private void broadcastSpawn(MapleMap map, MapleMonster monster) {
        LeaseOwner owner = pickController(map, monster);
        long exclude = owner == null ? -1 : owner.characterId();
        if (owner != null) {
            PacketSession s = sessions.get(owner.characterId());
            if (s != null) {
                s.send(GamePacketFactory.spawnMonsterControl(monster));
            }
        }
        sessions.broadcastToMap(map, GamePacketFactory.spawnMonster(monster), exclude);
    }

    /** 挑一个可接管该怪的在场玩家（有会话 + 不在冷却，tryClaim 成功即接管）。 */
    private LeaseOwner pickController(MapleMap map, MapleMonster monster) {
        for (CharacterState chr : map.characters()) {
            PacketSession session = sessions.get(chr.getId());
            if (session == null) {
                continue;
            }
            Long gen = session.getAttr("sessionGeneration");
            if (gen == null) {
                continue;
            }
            LeaseOwner owner = new LeaseOwner(chr.getId(), session.sessionId(), gen);
            if (leaseService.tryClaim(map.getMapId(), monster.getObjectId(), owner)) {
                return owner;
            }
        }
        return null;
    }

    /** 地图上是否已有该 mobId 的活怪（去重刷怪）。 */
    private boolean hasAliveMonster(MapleMap map, int mobId) {
        for (MapleMonster m : map.monsters()) {
            if (m.isAlive() && m.getData().getMobId() == mobId) {
                return true;
            }
        }
        return false;
    }

    /** 怪物死亡后延时重生（战斗 handler 在怪物死亡时调用）。 */
    public void scheduleRespawn(MapleMap map, SpawnPoint sp) {
        respawnScheduled.incrementAndGet();
        long delayMs = sp.getRespawnInterval() > 0 ? sp.getRespawnInterval() : 10_000;
        respawnScheduler.schedule(() -> {
            respawnExecuted.incrementAndGet();
            MapleMonster monster = spawnOne(map, sp);
            if (monster != null) {
                broadcastSpawn(map, monster);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** 可观测快照（做 Web/M5 管理端时读取，无需再埋点）。 */
    public SpawnStats stats() {
        return new SpawnStats(
                respawnScheduled.get(),
                respawnExecuted.get(),
                spawned.get(),
                missingMobData.get(),
                skippedDuplicate.get(),
                reassigned.get());
    }

    /** 刷怪服务统计快照（不可变）。 */
    public record SpawnStats(long respawnScheduled, long respawnExecuted, long spawned,
                             long missingMobData, long skippedDuplicate, long reassigned) {
    }

    /** 关闭调度器（频道服关闭时）。 */
    public void close() {
        respawnScheduler.shutdownNow();
    }
}
