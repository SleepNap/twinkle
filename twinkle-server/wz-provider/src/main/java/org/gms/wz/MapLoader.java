package org.gms.wz;

import org.gms.domain.game.map.MapleFoothold;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.Portal;
import org.gms.domain.game.map.PortalType;
import org.gms.domain.game.map.SpawnPoint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 地图加载器（架构 6.4：`twinkle.wz.path` 直接指定 WZ 目录，单份数据）。
 *
 * <p>从 {@code Map.wz/Map/Map<mapId/1e8>/<mapId>.img.xml} 按需解析单张地图，填充
 * {@link MapleMap} 的静态属性（info）、传送点（portal）与怪物刷新点（life 的 m 型）。
 * 找不到文件返回 {@link Optional#empty()}——调用方决定报错（架构 6.4：读不到即报错）。
 *
 * <p>只填充第一片所需字段；foothold/地图脚本等后续片补齐。
 */
public final class MapLoader {

    private final Path wzRoot;

    /**
     * @param wzRoot WZ 数据根目录（对应配置 {@code twinkle.wz.path}）
     */
    public MapLoader(Path wzRoot) {
        this.wzRoot = Objects.requireNonNull(wzRoot, "wzRoot");
    }

    /** 加载指定地图；文件不存在返回 empty。 */
    public Optional<MapleMap> load(int mapId) {
        Path file = mapFile(mapId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        WzNode root = WzXmlParser.parse(file);
        return Optional.of(fill(mapId, root));
    }

    private Path mapFile(int mapId) {
        int segment = mapId / 100_000_000;
        return wzRoot.resolve("Map.wz").resolve("Map")
                .resolve("Map" + segment)
                .resolve(mapId + ".img.xml");
    }

    private MapleMap fill(int mapId, WzNode root) {
        MapleMap map = new MapleMap();
        map.setMapId(mapId);

        root.child("info").ifPresent(info -> {
            map.setTown(info.getInt("town").orElse(0) == 1);
            info.getDouble("mobRate").ifPresent(rate -> map.setMonsterRate((int) rate));
            map.setReturnMapId(info.getInt("returnMap").orElse(mapId));
            map.setForcedReturnMap(info.getInt("forcedReturn").orElse(999_999_999));
            map.setFieldLimit(info.getInt("fieldLimit").orElse(0));
            info.getString("onUserEnter").ifPresent(map::setOnUserEnter);
            info.getString("onFirstUserEnter").ifPresent(map::setOnFirstUserEnter);
        });

        root.child("portal").ifPresent(portals ->
                portals.children().forEach((idx, p) -> map.putPortal(fillPortal(idx, p))));

        root.child("foothold").ifPresent(fhs ->
                fhs.children().forEach((layerName, layerNode) -> {
                    int layer = Integer.parseInt(layerName);
                    layerNode.children().forEach((groupName, groupNode) -> {
                        int group = Integer.parseInt(groupName);
                        groupNode.children().forEach((fhId, fhNode) ->
                                map.putFoothold(fillFoothold(fhId, layer, group, fhNode)));
                    });
                }));

        root.child("life").ifPresent(lifes ->
                lifes.children().forEach((idx, l) -> {
                    if ("m".equals(l.getString("type").orElse(""))) {
                        map.addSpawnPoint(fillSpawnPoint(l));
                    }
                    // 'n' = NPC，第一片不建结构，忽略
                }));

        return map;
    }

    private MapleFoothold fillFoothold(String fhId, int layer, int group, WzNode fh) {
        MapleFoothold foothold = new MapleFoothold(Integer.parseInt(fhId), layer, group);
        fh.getInt("x1").ifPresent(foothold::setX1);
        fh.getInt("y1").ifPresent(foothold::setY1);
        fh.getInt("x2").ifPresent(foothold::setX2);
        fh.getInt("y2").ifPresent(foothold::setY2);
        fh.getInt("prev").ifPresent(foothold::setPrev);
        fh.getInt("next").ifPresent(foothold::setNext);
        fh.getInt("force").ifPresent(foothold::setForce);
        return foothold;
    }

    private Portal fillPortal(String idx, WzNode p) {        Portal portal = new Portal(
                Integer.parseInt(idx),
                p.getString("pn").orElse(""),
                PortalType.fromType((byte) p.getInt("pt").orElse(0)),
                p.getInt("x").orElse(0),
                p.getInt("y").orElse(0));
        p.getInt("tm").ifPresent(portal::setTargetMapId);
        p.getString("tn").ifPresent(portal::setTargetPortalName);
        return portal;
    }

    private SpawnPoint fillSpawnPoint(WzNode l) {
        int id = Integer.parseInt(l.getString("id").orElse("0"));
        SpawnPoint spawn = new SpawnPoint(id, l.getInt("x").orElse(0), l.getInt("y").orElse(0));
        l.getInt("mobTime").ifPresent(t -> spawn.setRespawnInterval(t * 1000)); // 秒 → 毫秒
        return spawn;
    }
}
