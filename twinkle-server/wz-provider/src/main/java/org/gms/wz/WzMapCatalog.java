package org.gms.wz;

import org.gms.domain.game.map.MapleMap;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 单代地图静态数据目录；地图按 id 延迟解析，同一代内只解析一次。 */
public final class WzMapCatalog {

    private final MapLoader loader;
    private final ConcurrentMap<Integer, Optional<MapleMap>> maps = new ConcurrentHashMap<>();

    public WzMapCatalog(Path wzRoot) {
        this.loader = new MapLoader(wzRoot);
    }

    public Optional<MapleMap> get(int mapId) {
        return maps.computeIfAbsent(mapId, loader::load);
    }

    public int cachedCount() {
        return maps.size();
    }
}
