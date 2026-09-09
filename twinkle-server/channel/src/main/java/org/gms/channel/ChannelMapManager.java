package org.gms.channel;

import org.gms.domain.game.map.MapleMap;
import org.gms.i18n.I18n;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.wz.WzReloadParticipant;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.WzResources;
import org.gms.wz.WzMapCatalog;
import org.gms.concurrent.GameExecution;

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

    public record PreparedStaticData(WzMapCatalog maps, Map<Integer, MobData> mobs) {
        public PreparedStaticData { mobs = Map.copyOf(mobs); }
    }

    private record Projection(Map<Integer, MapleMap> replacements, Map<MapleMonster, MobData> monsters) {
        private Projection {
            replacements = Map.copyOf(replacements);
            monsters = Map.copyOf(monsters);
        }
    }

    private final WzResourceRegistry.View resourceView;
    private final GameExecution execution;
    private final String participantName;
    private final ConcurrentMap<Integer, MapleMap> maps = new ConcurrentHashMap<>();

    public ChannelMapManager(WzResourceRegistry resources) {
        this(resources, null);
    }

    public ChannelMapManager(WzResourceRegistry resources, Integer channelId) {
        this(resources, channelId, null);
    }

    public ChannelMapManager(WzResourceRegistry resources, Integer channelId, GameExecution execution) {
        this.resourceView = resources.view();
        this.execution = execution;
        this.participantName = channelId == null ? "channel-maps" : "channel-maps:" + channelId;
        if (execution != null) execution.bindOperationScope(resourceView::run);
    }

    /** 取地图（不存在报错——架构 6.4：读不到即报错）。 */
    public MapleMap getMap(int mapId) {
        if (execution != null && !execution.isOwner()) return execution.call(() -> getMap(mapId));
        return maps.computeIfAbsent(mapId, id -> {
            MapleMap template = resourceView.resource(WzResources.MAPS).get(id)
                    .orElseThrow(() -> new IllegalArgumentException(I18n.message("error.map.not_found", id)));
            MapleMap runtime = new MapleMap();
            runtime.replaceWzData(template);
            return runtime;
        });
    }

    /**
     * 把当前 WZ 版本应用到全部已加载地图。先解析完所有地图，再统一修改运行态对象；解析失败时不动旧地图。
     *
     * @return 更新的已加载地图数
     */
    @Override
    public String name() {
        return participantName;
    }

    /** 本频道已经提交的 WZ 代际，可能在滚动发布期间落后于全局候选。 */
    public long resourceVersion() { return resourceView.version(); }

    @Override
    public PreparedChange prepare(WzResourceRegistry.PreparedReload preparedResources) {
        PreparedStaticData prepared = prepareReload(
                preparedResources.resource(WzResources.MAPS),
                preparedResources.resource(WzResources.MOBS));
        return () -> publish(preparedResources, prepared);
    }

    private int publish(WzResourceRegistry.PreparedReload resources, PreparedStaticData prepared) {
        if (execution != null && !execution.isOwner()) return execution.call(() -> publish(resources, prepared));
        resourceView.validate(resources);
        int changed = commitReload(prepared);
        resourceView.publish(resources);
        return changed;
    }

    public PreparedStaticData prepareReload(WzMapCatalog mapsCatalog, Map<Integer, MobData> mobData) {
        if (execution != null && !execution.isOwner()) return execution.call(() -> prepareReload(mapsCatalog, mobData));
        PreparedStaticData prepared = new PreparedStaticData(mapsCatalog, mobData);
        project(prepared);
        return prepared;
    }

    private Projection project(PreparedStaticData prepared) {
        Map<Integer, MapleMap> replacements = new LinkedHashMap<>();
        Map<MapleMonster, MobData> monsters = new LinkedHashMap<>();
        for (Integer mapId : maps.keySet()) {
            MapleMap replacement = prepared.maps().get(mapId)
                    .orElseThrow(() -> new IllegalArgumentException(I18n.message("error.map.not_found", mapId)));
            replacements.put(mapId, replacement);
            for (MapleMonster monster : maps.get(mapId).monsters()) {
                MobData replacementData = prepared.mobs().get(monster.getData().getMobId());
                if (replacementData == null) {
                    throw new IllegalArgumentException("WZ mob not found: " + monster.getData().getMobId());
                }
                monsters.put(monster, replacementData);
            }
        }
        return new Projection(replacements, monsters);
    }

    public int commitReload(PreparedStaticData prepared) {
        if (execution != null && !execution.isOwner()) return execution.call(() -> commitReload(prepared));
        // 准备后仍可进图、刷怪和杀怪；提交前重新投影当前存活对象，不能保存旧对象引用。
        Projection projection = project(prepared);
        projection.replacements().forEach((mapId, replacement) -> maps.get(mapId).replaceWzData(replacement));
        projection.monsters().forEach(MapleMonster::replaceWzData);
        return projection.replacements().size() + projection.monsters().size();
    }

    /** 全部已加载地图（租约巡检/无主怪重新分配用，不可变视图）。 */
    public java.util.Collection<MapleMap> maps() {
        return java.util.List.copyOf(maps.values());
    }
}
