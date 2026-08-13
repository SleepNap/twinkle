package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.i18n.I18n;
import org.gms.wz.MapLoader;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 频道地图管理器（架构 M2 进图：WZ 地图加载 + 每频道缓存）。
 *
 * <p>同频道内地图按 mapId 缓存一次，玩家进出共享同一 {@link MapleMap}（内存态权威）。
 * 秒级重开的 WZ 预编译缓存（M2-3 余项）后续接入时替换此处的 {@link MapLoader} 数据源。
 */
public final class ChannelMapManager {

    private final MapLoader loader;
    private final ConcurrentMap<Integer, MapleMap> maps = new ConcurrentHashMap<>();

    public ChannelMapManager(MapLoader loader) {
        this.loader = loader;
    }

    /** 取地图（不存在报错——架构 6.4：读不到即报错）。 */
    public MapleMap getMap(int mapId) {
        return maps.computeIfAbsent(mapId,
                id -> loader.load(id).orElseThrow(() -> new IllegalArgumentException(I18n.message("error.map.not_found", id))));
    }

    /** 全部已加载地图（租约巡检/无主怪重新分配用，不可变视图）。 */
    public java.util.Collection<MapleMap> maps() {
        return java.util.List.copyOf(maps.values());
    }
}
