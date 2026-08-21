package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.tick.TickHandler;

/**
 * 无主怪周期重新分配（事故报告阶段 B：租约超时释放后，健康玩家已在场时的接管）。
 *
 * <p>挂在既有游戏 tick（不新增线程）。按构造时换算出的业务周期遍历全部已加载地图，
 * 对有人地图调 {@link MonsterSpawnService#reassign}。
 *
 * <p>可观测口子：无自有状态（纯调度）；异常经 GameTickLoop 记录并继续下一 tick。
 */
public final class MonsterReassignTickHandler implements TickHandler {

    private final ChannelMapManager mapManager;
    private final MonsterSpawnService spawnService;
    private final long everyTicks;

    public MonsterReassignTickHandler(ChannelMapManager mapManager, MonsterSpawnService spawnService,
                                      long everyTicks) {
        if (everyTicks <= 0) {
            throw new IllegalArgumentException("everyTicks must be positive");
        }
        this.mapManager = mapManager;
        this.spawnService = spawnService;
        this.everyTicks = everyTicks;
    }

    @Override
    public void tick(long tickCount) {
        if (tickCount % everyTicks == 0) {
            for (MapleMap map : mapManager.maps()) {
                spawnService.reassign(map);
            }
        }
    }
}
