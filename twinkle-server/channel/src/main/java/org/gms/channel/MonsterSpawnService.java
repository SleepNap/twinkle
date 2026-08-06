package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.SpawnPoint;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.net.packet.OutPacket;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 怪物刷怪服务（M3-5：SpawnPoint → 实例化 → 重生调度）。
 *
 * <p>地图按 {@code SpawnPoint}（monsterId/x/y/respawnInterval/chance）+ {@code MobData}
 * 实例化 {@link MapleMonster}、分配 objectId、加入地图；玩家进图时发 {@code SPAWN_MONSTER}。
 * 怪物死亡后按 respawnInterval 延时重生（低配独立单线程调度器，2C2G 预算可控）。
 *
 * <p>掉落（DROP_ITEM_FROM_MAPOBJECT）留后续：WZ 掉率表（mob drops）未解析。
 * 只做"生成 + 重生"，死亡广播由战斗 handler 负责。
 *
 * <p><b>可观测口子</b>（用户要求：任务/流程/子线程可监控，勿做隐式）：自带原子计数
 * 与快照查询（{@link #stats()}），做 Web/M5 管理端时直接可读，不依赖未装配的
 * Metrics bean。重生调度器的生命周期/异常经 {@link #close} 与计数暴露。
 */
public final class MonsterSpawnService {

    private static final Logger LOG = LogManager.getLogger(MonsterSpawnService.class);

    private final Map<Integer, MobData> mobData;
    private final PlayerSessionRegistry sessions;
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

    public MonsterSpawnService(Map<Integer, MobData> mobData, PlayerSessionRegistry sessions) {
        this.mobData = mobData;
        this.sessions = sessions;
    }

    /** 按地图刷怪点生成怪物并广播（进图时调用）。 */
    public void spawnForMap(MapleMap map) {
        for (SpawnPoint sp : map.spawnPoints()) {
            MapleMonster monster = spawnOne(map, sp);
            if (monster != null) {
                sessions.broadcastToMap(map, GamePacketFactory.spawnMonster(monster));
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
            LOG.warn("刷怪缺 MobData: monsterId={}", sp.getMonsterId());
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

    /** 怪物死亡后延时重生（战斗 handler 在怪物死亡时调用）。 */
    public void scheduleRespawn(MapleMap map, SpawnPoint sp) {
        respawnScheduled.incrementAndGet();
        long delayMs = sp.getRespawnInterval() > 0 ? sp.getRespawnInterval() : 10_000;
        respawnScheduler.schedule(() -> {
            respawnExecuted.incrementAndGet();
            MapleMonster monster = spawnOne(map, sp);
            if (monster != null) {
                sessions.broadcastToMap(map, GamePacketFactory.spawnMonster(monster));
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** 可观测快照（做 Web/M5 管理端时读取，无需再埋点）。 */
    public SpawnStats stats() {
        return new SpawnStats(
                respawnScheduled.get(),
                respawnExecuted.get(),
                spawned.get(),
                missingMobData.get());
    }

    /** 刷怪服务统计快照（不可变）。 */
    public record SpawnStats(long respawnScheduled, long respawnExecuted, long spawned, long missingMobData) {
    }

    /** 关闭调度器（频道服关闭时）。 */
    public void close() {
        respawnScheduler.shutdownNow();
    }
}
