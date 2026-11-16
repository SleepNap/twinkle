package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.Portal;
import org.gms.domain.game.map.PortalType;
import org.gms.domain.game.lease.ControllerLeaseService;
import org.gms.domain.game.lease.LeaseOwner;
import org.gms.domain.script.ScriptManager;
import org.gms.i18n.I18n;
import org.gms.net.packet.PacketSession;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** 统一处理客户端门、复活与脚本传送；目标地图和落点始终由服务端选择。 */
@Log4j2
public final class MapTransitionService {

    /** v83 复活轮（现金栏）；死亡后原地复活并消耗 1 个。 */
    private static final int WHEEL_OF_FORTUNE_ITEM_ID = 5_510_000;

    private final IntFunction<MapleMap> maps;
    private final MonsterSpawnService monsters;
    private final ControllerLeaseService leases;
    private final int channelId;
    private final PlayerSessionRegistry sessions;
    private final ScriptManager scripts;

    public MapTransitionService(IntFunction<MapleMap> maps, MonsterSpawnService monsters,
                                ControllerLeaseService leases, int channelId) {
        this(maps, monsters, leases, channelId, null, null);
    }

    public MapTransitionService(IntFunction<MapleMap> maps, MonsterSpawnService monsters,
                                ControllerLeaseService leases, int channelId, PlayerSessionRegistry sessions) {
        this(maps, monsters, leases, channelId, sessions, null);
    }

    public MapTransitionService(IntFunction<MapleMap> maps, MonsterSpawnService monsters,
                                ControllerLeaseService leases, int channelId, PlayerSessionRegistry sessions,
                                ScriptManager scripts) {
        this.maps = maps;
        this.monsters = monsters;
        this.leases = leases;
        this.channelId = channelId;
        this.sessions = sessions;
        this.scripts = scripts;
    }

    public boolean usePortal(PacketSession session, String name, int requestedMap) {
        PlayerCharacter character = GameplaySession.character(session);
        if (!GameplaySession.canAct(session, character)) return false;
        Portal portal = character.getMapObject().portals().stream()
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElse(null);
        if (portal == null || !GameplaySession.near(character, portal.getX(), portal.getY(), 150)) return false;
        if (portal.isScript()) return runPortalScript(session, character, portal);
        if (portal.getTargetMapId() == 999_999_999
                || requestedMap >= 0 && requestedMap != portal.getTargetMapId()) return false;
        return transfer(session, portal.getTargetMapId(), portal.getTargetPortalName(), false);
    }

    /**
     * 执行 v83 脚本门（{@code scripts/portal/<scriptName>.js}）。
     *
     * <p>脚本没主动传送、且该门同时带普通目标地图时回退到默认传送——v83 里不少脚本门既跑脚本
     * 又保留目标地图；脚本抛异常只记录日志并拒绝本次传送，不会让频道线程崩掉。
     */
    private boolean runPortalScript(PacketSession session, PlayerCharacter character, Portal portal) {
        String scriptName = portal.getScriptName();
        if (scripts == null || scriptName == null || scriptName.isBlank()) return false;
        PortalScriptHost host = new PortalScriptHost(session, character, this, portal);
        try {
            scripts.run("portal/" + scriptName, Map.of("portal", host));
        } catch (RuntimeException error) {
            log.error(I18n.message("log.script.portal_failed"), scriptName, error);
            return false;
        }
        if (host.warped()) return true;
        if (portal.getTargetMapId() > 0 && portal.getTargetMapId() != 999_999_999) {
            return transfer(session, portal.getTargetMapId(), portal.getTargetPortalName(), false);
        }
        return false;
    }

    /**
     * 供脚本宿主调用的传图入口。
     *
     * <p>{@code portalName} 为空或目标地图没有该落点时，回退到目标地图的第一个传送点，
     * 避免脚本只写 {@code warp(mapId)} 时因为找不到落点而整次失败。
     */
    boolean warpTo(PacketSession session, int mapId, String portalName) {
        if (mapId <= 0) return false;
        MapleMap target;
        try {
            target = maps.apply(mapId);
        } catch (IllegalArgumentException error) {
            return false;
        }
        if (target == null) return false;
        final String requested = portalName;
        boolean found = requested != null && target.portals().stream()
                .anyMatch(portal -> portal.getName().equals(requested));
        String name = requested;
        if (!found) {
            // 默认落点优先 sp（v83 出生点约定），其次城镇/地图传送门，最后任意非脚本点；
            // 不能直接取 portals() 的第一个——底层是 HashMap，顺序不保证，可能挑到脚本门。
            List<Portal> candidates = target.portals().stream()
                    .filter(portal -> !portal.isScript())
                    .toList();
            name = candidates.stream()
                    .filter(portal -> "sp".equals(portal.getName()))
                    .map(Portal::getName)
                    .findFirst()
                    .orElseGet(() -> candidates.stream()
                            .filter(portal -> portal.getType() == PortalType.MAP_PORTAL
                                    || portal.getType() == PortalType.TOWN_PORTAL)
                            .map(Portal::getName)
                            .filter(candidate -> candidate != null && !candidate.isBlank())
                            .findFirst()
                            .orElseGet(() -> candidates.stream()
                                    .map(Portal::getName)
                                    .filter(candidate -> candidate != null && !candidate.isBlank())
                                    .findFirst()
                                    .orElse(null)));
        }
        if (name == null) return false;
        return transfer(session, mapId, name, false);
    }

    public boolean revive(PacketSession session) {
        return revive(session, false);
    }

    /**
     * 死亡复活：请求复活轮且持有 `5510000` 时回到当前地图最近出生点；否则回城。
     *
     * <p>普通复活恢复 50 HP；本次死亡消耗过安全护符时按最大 HP/MP 的 30% 恢复，
     * 与 v83 一致。复活轮复活只恢复 50 HP。
     */
    public boolean revive(PacketSession session, boolean wheel) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null || character.getMapObject() == null) return false;
        if (character.getHp() > 0 || session.getAttr("trade") != null) return false;
        if (wheel && character.getItemCount(WHEEL_OF_FORTUNE_ITEM_ID) > 0) {
            return reviveWithWheel(session, character);
        }
        return transfer(session, character.getMapObject().getReturnMapId(), "sp", true);
    }

    /** 仅供可信脚本宿主使用，不接受客户端自选地图作为授权依据。 */
    public boolean warp(PacketSession session, int mapId) {
        PlayerCharacter character = GameplaySession.character(session);
        if (!GameplaySession.canAct(session, character)) return false;
        return transfer(session, mapId, "sp", false);
    }

    private boolean transfer(PacketSession session, int mapId, String portalName, boolean revive) {
        PlayerCharacter character = GameplaySession.character(session);
        MapleMap target;
        try { target = maps.apply(mapId); }
        catch (IllegalArgumentException error) { return false; }
        if (target == null) return false;
        Portal destination = target.portals().stream().filter(portal -> portal.getName().equals(portalName))
                .min(Comparator.comparingInt(Portal::getId)).orElse(null);
        if (destination == null || destination.getId() < 0 || destination.getId() > 255) return false;
        // 先完成全部查找及校验，防止错误目标使角色消失在原地图。
        leaveMap(session, character);
        if (revive) applyReviveHealth(session, character);
        character.setMap(mapId);
        character.setSpawnPoint(destination.getId());
        character.setX(destination.getX());
        character.setY(destination.getY());
        character.setMapObject(target);
        target.addCharacter(character);
        character.markDirty();
        session.send(GameplayPackets.warp(character, channelId));
        populate(session);
        return true;
    }

    /** 复活轮：消耗现金栏物品后留在当前地图最近出生点，只恢复 50 HP。 */
    private boolean reviveWithWheel(PacketSession session, PlayerCharacter character) {
        MapleMap map = character.getMapObject();
        var before = GameplayPackets.inventory(character, InventoryType.CASH);
        if (!character.removeItem(WHEEL_OF_FORTUNE_ITEM_ID, 1)) return false;
        GameplayPackets.inventoryChanges(InventoryType.CASH, before,
                GameplayPackets.inventory(character, InventoryType.CASH)).forEach(session::send);
        session.send(GameplayPackets.wheelsLeft(character.getItemCount(WHEEL_OF_FORTUNE_ITEM_ID)));
        Portal spawn = map.closestSpawnPortal(character.getX(), character.getY());
        leaveMap(session, character);
        session.setAttr("safetyCharmUsed", null);
        character.setHp(Math.min(character.effectiveMaxHp(), 50));
        character.setSpawnPoint(spawn == null ? 0 : Math.min(255, spawn.getId()));
        if (spawn != null) {
            character.setX(spawn.getX());
            character.setY(spawn.getY());
        }
        character.setMapObject(map);
        map.addCharacter(character);
        character.markDirty();
        session.send(GameplayPackets.warp(character, channelId));
        populate(session);
        return true;
    }

    /** 离开当前地图并释放控制租约；调用前必须完成目标校验。 */
    private void leaveMap(PacketSession session, PlayerCharacter character) {
        session.setAttr("mapTransition", true);
        session.setAttr("npcShop", null);
        // 仓库窗口与 NPC 会话同生命周期：换图后旧 NPC 的仓库操作必须失效。
        session.setAttr(StorageService.SESSION_ATTR, null);
        if (sessions != null) sessions.visibility().leave(session);
        if (sessions != null) session.setAttr("mapVisibilityReady", false);
        character.setChairItemId(0);
        character.setStance(0);
        character.setFoothold(0);
        character.getMapObject().removeCharacter(character);
        Long generation = session.getAttr("sessionGeneration");
        if (leases != null && generation != null) leases.onDisconnect(character.getId(), session.sessionId(), generation);
    }

    /** 普通复活 50 HP；安全护符生效时改为最大 HP/MP 的 30%。 */
    private static void applyReviveHealth(PacketSession session, PlayerCharacter character) {
        boolean charm = Boolean.TRUE.equals(session.getAttr("safetyCharmUsed"));
        session.setAttr("safetyCharmUsed", null);
        if (!charm) {
            character.setHp(Math.min(character.effectiveMaxHp(), 50));
            return;
        }
        character.setHp(Math.max(1, Math.min(character.effectiveMaxHp(),
                (int) Math.ceil(character.effectiveMaxHp() * 0.3))));
        character.setMp(Math.min(character.effectiveMaxMp(),
                character.getMp() + (int) Math.ceil(character.effectiveMaxMp() * 0.3)));
    }

    public void populate(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null || character.getMapObject() == null) return;
        MapleMap map = character.getMapObject();
        map.npcs().stream().sorted(Comparator.comparingInt(npc -> npc.objectId()))
                .forEach(npc -> session.send(GameplayPackets.npc(npc)));
        if (monsters != null) {
            monsters.ensureSpawned(map);
            Long generation = session.getAttr("sessionGeneration");
            if (generation != null) {
                if (leases != null) leases.onClaim(character.getId(), session.sessionId(), generation);
                monsters.onPlayerEnter(map, session, new LeaseOwner(character.getId(), session.sessionId(), generation));
            }
        }
    }
}
