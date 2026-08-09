package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.tick.TickHandler;

/**
 * 无主怪周期重新分配（事故报告阶段 B：租约超时释放后，健康玩家已在场时的接管）。
 *
 * <p>挂在既有游戏 tick（不新增线程）。每 {@code everyTicks} tick（默认 100 = 10s，与
 * 租约巡检同周期）遍历全部已加载地图，对有人地图调 {@link MonsterSpawnService#reassign}。
 *
 * <p>可观测口子：无自有状态（纯调度）；异常经 GameTickLoop 记录并继续下一 tick。
 */
public final class MonsterReassignTickHandler implements TickHandler {

    /** 每 N tick 重分配一次（100ms tick × 100 = 10s）。 */
    private static final long EVERY_TICKS = 100;

    private final ChannelMapManager mapManager;
    private final MonsterSpawnService spawnService;

    public MonsterReassignTickHandler(ChannelMapManager mapManager, MonsterSpawnService spawnService) {
        this.mapManager = mapManager;
        this.spawnService = spawnService;
    }

    @Override
    public void tick(long tickCount) {
        if (tickCount % EVERY_TICKS == 0) {
            for (MapleMap map : mapManager.maps()) {
                spawnService.reassign(map);
            }
        }
    }
}
