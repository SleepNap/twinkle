package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.Portal;
import org.gms.domain.game.lease.ControllerLeaseService;
import org.gms.domain.game.lease.LeaseOwner;
import org.gms.net.packet.PacketSession;

import java.util.Comparator;
import java.util.function.IntFunction;

/** 统一处理客户端门、复活与脚本传送；目标地图和落点始终由服务端选择。 */
public final class MapTransitionService {
    private final IntFunction<MapleMap> maps;
    private final MonsterSpawnService monsters;
    private final ControllerLeaseService leases;
    private final int channelId;
    private final PlayerSessionRegistry sessions;

    public MapTransitionService(IntFunction<MapleMap> maps, MonsterSpawnService monsters,
                                ControllerLeaseService leases, int channelId) {
        this(maps, monsters, leases, channelId, null);
    }

    public MapTransitionService(IntFunction<MapleMap> maps, MonsterSpawnService monsters,
                                ControllerLeaseService leases, int channelId, PlayerSessionRegistry sessions) {
        this.maps = maps;
        this.monsters = monsters;
        this.leases = leases;
        this.channelId = channelId;
        this.sessions = sessions;
    }

    public boolean usePortal(PacketSession session, String name, int requestedMap) {
        PlayerCharacter character = GameplaySession.character(session);
        if (!GameplaySession.canAct(session, character)) return false;
        synchronized (character) {
            Portal portal = character.getMapObject().portals().stream()
                    .filter(candidate -> candidate.getName().equals(name)).findFirst().orElse(null);
            if (portal == null || portal.isScript() || !GameplaySession.near(character, portal.getX(), portal.getY(), 150)
                    || portal.getTargetMapId() == 999_999_999
                    || requestedMap >= 0 && requestedMap != portal.getTargetMapId()) return false;
            return transfer(session, portal.getTargetMapId(), portal.getTargetPortalName(), false);
        }
    }

    public boolean revive(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        if (character == null || character.getMapObject() == null) return false;
        synchronized (character) {
            if (character.getHp() > 0 || session.getAttr("trade") != null) return false;
            return transfer(session, character.getMapObject().getReturnMapId(), "sp", true);
        }
    }

    /** 仅供可信脚本宿主使用，不接受客户端自选地图作为授权依据。 */
    public boolean warp(PacketSession session, int mapId) {
        PlayerCharacter character = GameplaySession.character(session);
        if (!GameplaySession.canAct(session, character)) return false;
        synchronized (character) { return transfer(session, mapId, "sp", false); }
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
        session.setAttr("mapTransition", true);
        session.setAttr("npcShop", null);
        if (sessions != null) sessions.visibility().leave(session);
        if (sessions != null) session.setAttr("mapVisibilityReady", false);
        character.setChairItemId(0);
        character.setStance(0);
        character.setFoothold(0);
        character.getMapObject().removeCharacter(character);
        Long generation = session.getAttr("sessionGeneration");
        if (leases != null && generation != null) leases.onDisconnect(character.getId(), session.sessionId(), generation);
        if (revive) character.setHp(Math.min(character.getMaxHp(), 50));
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
