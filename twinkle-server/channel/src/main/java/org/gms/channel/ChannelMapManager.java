package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.i18n.I18n;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.wz.WzReloadParticipant;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.WzResources;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 频道地图管理器（架构 M2 进图：WZ 地图加载 + 每频道缓存）。
 *
 * <p>同频道内地图按 mapId 缓存一次，玩家进出共享同一 {@link MapleMap}（内存态权威）。
 * WZ 重载时只替换地图静态数据，保留在线玩家、怪物和对象 id 等运行态容器。
 */
public final class ChannelMapManager implements WzReloadParticipant {

    public record PreparedStaticData(Map<Integer, MapleMap> replacements,
                                     Map<MapleMonster, MobData> monsters) {
        public PreparedStaticData {
            replacements = Map.copyOf(replacements);
            monsters = Map.copyOf(monsters);
        }
    }

    private final WzResourceRegistry resources;
    private final ConcurrentMap<Integer, MapleMap> maps = new ConcurrentHashMap<>();

    public ChannelMapManager(WzResourceRegistry resources) {
        this.resources = resources;
    }

    /** 取地图（不存在报错——架构 6.4：读不到即报错）。 */
    public MapleMap getMap(int mapId) {
        return maps.computeIfAbsent(mapId,
                id -> resources.resource(WzResources.MAPS).get(id)
                        .orElseThrow(() -> new IllegalArgumentException(I18n.message("error.map.not_found", id))));
    }

    /**
     * 把当前 WZ 版本应用到全部已加载地图。先解析完所有地图，再统一修改运行态对象；解析失败时不动旧地图。
     *
     * @return 更新的已加载地图数
     */
    @Override
    public String name() {
        return "channel-maps";
    }

    @Override
    public synchronized PreparedChange prepare(WzResourceRegistry.PreparedReload preparedResources) {
        PreparedStaticData prepared = prepareReload(
                preparedResources.resource(WzResources.MAPS),
                preparedResources.resource(WzResources.MOBS));
        return () -> commitReload(prepared);
    }

    public synchronized PreparedStaticData prepareReload(org.gms.wz.WzMapCatalog mapsCatalog,
                                                         Map<Integer, MobData> mobData) {
        Map<Integer, MapleMap> replacements = new LinkedHashMap<>();
        Map<MapleMonster, MobData> monsters = new LinkedHashMap<>();
        for (Integer mapId : maps.keySet()) {
            MapleMap replacement = mapsCatalog.get(mapId)
                    .orElseThrow(() -> new IllegalArgumentException(I18n.message("error.map.not_found", mapId)));
            replacements.put(mapId, replacement);
            for (MapleMonster monster : maps.get(mapId).monsters()) {
                MobData replacementData = mobData.get(monster.getData().getMobId());
                if (replacementData == null) {
                    throw new IllegalArgumentException("WZ mob not found: " + monster.getData().getMobId());
                }
                monsters.put(monster, replacementData);
            }
        }
        return new PreparedStaticData(replacements, monsters);
    }

    public synchronized int commitReload(PreparedStaticData prepared) {
        prepared.replacements().forEach((mapId, replacement) -> maps.get(mapId).replaceWzData(replacement));
        prepared.monsters().forEach(MapleMonster::replaceWzData);
        return prepared.replacements().size() + prepared.monsters().size();
    }

    /** 全部已加载地图（租约巡检/无主怪重新分配用，不可变视图）。 */
    public java.util.Collection<MapleMap> maps() {
        return java.util.List.copyOf(maps.values());
    }
}
