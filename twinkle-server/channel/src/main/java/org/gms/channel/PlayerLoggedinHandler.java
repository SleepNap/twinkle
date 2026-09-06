package org.gms.channel;

import lombok.extern.log4j.Log4j2;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 玩家登录进图处理（RecvOpcode.PLAYER_LOGGEDIN）。
 *
 * <p>客户端选角后重连频道服，握手后发本包。流程：按 charId 加载完整存档 →
 * 投影为内存态角色（PlayerCharacterAssembler）→ 注册频道在线表 + 会话注册表 →
 * 放入目标地图 → 回 getCharInfo（SET_FIELD，客户端据此刻画角色并进入地图）。
 */
@Log4j2
public final class PlayerLoggedinHandler implements PacketHandler {


    private final PlayerCharacterRepository characterRepo;
    private final PlayerCharacterAssembler characterLoader;
    private final ChannelMapManager mapManager;
    private final PlayerStorage players;
    private final PlayerSessionRegistry sessions;
    private final MonsterSpawnService spawnService;
    private final org.gms.domain.game.lease.ControllerLeaseService leaseService;
    private final int channelId;
    private final org.gms.channel.admin.ChannelEventPublisher eventPublisher;

    public PlayerLoggedinHandler(PlayerCharacterRepository characterRepo, PlayerCharacterAssembler characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players,
                                 PlayerSessionRegistry sessions, MonsterSpawnService spawnService, int channelId) {
        this(characterRepo, characterLoader, mapManager, players, sessions, spawnService, channelId, null, null);
    }

    public PlayerLoggedinHandler(PlayerCharacterRepository characterRepo, PlayerCharacterAssembler characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players,
                                 PlayerSessionRegistry sessions, MonsterSpawnService spawnService, int channelId,
                                 org.gms.channel.admin.ChannelEventPublisher eventPublisher) {
        this(characterRepo, characterLoader, mapManager, players, sessions, spawnService, channelId, eventPublisher, null);
    }

    public PlayerLoggedinHandler(PlayerCharacterRepository characterRepo, PlayerCharacterAssembler characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players,
                                 PlayerSessionRegistry sessions, MonsterSpawnService spawnService, int channelId,
                                 org.gms.channel.admin.ChannelEventPublisher eventPublisher,
                                 org.gms.domain.game.lease.ControllerLeaseService leaseService) {
        this.characterRepo = characterRepo;
        this.characterLoader = characterLoader;
        this.mapManager = mapManager;
        this.players = players;
        this.sessions = sessions;
        this.spawnService = spawnService;
        this.channelId = channelId;
        this.eventPublisher = eventPublisher;
        this.leaseService = leaseService;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.LOGIN) {
            session.close(I18n.message("error.player_login.outside_stage"));
            return;
        }
        long charId = packet.readInt();
        var dbChar = characterRepo.findById(charId).orElse(null);
        if (dbChar == null) {
            session.close(I18n.message("error.player_login.character_not_found", charId));
            return;
        }
        PlayerCharacter chr = characterLoader.fromData(dbChar);
        MapleMap map = mapManager.getMap(chr.getMap());
        chr.setMapObject(map);
        // 会话代际认领（事故报告阶段 B）：新连接认领 = 新代际；先移除地图/在线表里
        // 同 id 的旧 PlayerCharacter（防广播双发），再由 claim 覆盖会话登记。
        removeSupersededCharacter(map, chr);
        map.addCharacter(chr);
        players.add(chr);
        long generation = sessions.claim(chr.getId(), session);
        session.setAttr("sessionGeneration", generation);
        if (leaseService != null) {
            // 新认领：旧代际租约立即失效（SESSION_REPLACED）
            leaseService.onClaim(chr.getId(), session.sessionId(), generation);
        }
        session.setAttr("character", chr);
        session.transition(SessionStage.IN_GAME);
        var portal = map.getPortal(chr.getSpawnPoint());
        if (portal != null) { chr.setX(portal.getX()); chr.setY(portal.getY()); }
        session.send(ChannelPacketFactory.charInfo(chr, channelId));
        // 地图对象必须在 SET_FIELD 之后发送，否则客户端尚未创建场景。
        map.npcs().forEach(npc -> session.send(GameplayPackets.npc(npc)));
        spawnService.ensureSpawned(map);
        spawnService.onPlayerEnter(map, session, new org.gms.domain.game.lease.LeaseOwner(
                chr.getId(), session.sessionId(), generation));
        if (eventPublisher != null) {
            eventPublisher.playerOnline(chr);
        }
        log.info(I18n.message("log.player_login.entered_map"), chr.getName(), chr.getId(), map.getMapId());
    }

    /** 移除地图/在线表里同 id 的非自身旧 PlayerCharacter（重复登录，防广播双发；旧代际断链迟到清理由 compare-and-remove 短路）。 */
    private void removeSupersededCharacter(MapleMap map, PlayerCharacter newChr) {
        for (var c : java.util.List.copyOf(map.characters())) {
            if (c.getId() == newChr.getId() && c != newChr) {
                map.removeCharacter(c);
                players.remove((PlayerCharacter) c);
            }
        }
    }
}
