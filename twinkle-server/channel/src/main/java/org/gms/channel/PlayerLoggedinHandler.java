package org.gms.channel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.data.repo.CharacterRepository;
import org.gms.domain.game.Character;
import org.gms.domain.game.map.MapleMap;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 玩家登录进图处理（RecvOpcode.PLAYER_LOGGEDIN）。
 *
 * <p>客户端选角后重连频道服，握手后发本包。流程：按 charId 加载完整存档 →
 * 投影为内存态角色（CharacterLoader）→ 注册频道在线表 + 会话注册表 →
 * 放入目标地图 → 回 getCharInfo（SET_FIELD，客户端据此刻画角色并进入地图）。
 */
public final class PlayerLoggedinHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(PlayerLoggedinHandler.class);

    private final CharacterRepository characterRepo;
    private final CharacterLoader characterLoader;
    private final ChannelMapManager mapManager;
    private final PlayerStorage players;
    private final PlayerSessionRegistry sessions;
    private final MonsterSpawnService spawnService;
    private final org.gms.domain.game.lease.ControllerLeaseService leaseService;
    private final int channelId;
    private final org.gms.channel.admin.ChannelEventPublisher eventPublisher;

    public PlayerLoggedinHandler(CharacterRepository characterRepo, CharacterLoader characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players,
                                 PlayerSessionRegistry sessions, MonsterSpawnService spawnService, int channelId) {
        this(characterRepo, characterLoader, mapManager, players, sessions, spawnService, channelId, null, null);
    }

    public PlayerLoggedinHandler(CharacterRepository characterRepo, CharacterLoader characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players,
                                 PlayerSessionRegistry sessions, MonsterSpawnService spawnService, int channelId,
                                 org.gms.channel.admin.ChannelEventPublisher eventPublisher) {
        this(characterRepo, characterLoader, mapManager, players, sessions, spawnService, channelId, eventPublisher, null);
    }

    public PlayerLoggedinHandler(CharacterRepository characterRepo, CharacterLoader characterLoader,
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
            session.close("阶段外收到进图包");
            return;
        }
        long charId = packet.readInt();
        var dbChar = characterRepo.findById(charId).orElse(null);
        if (dbChar == null) {
            session.close("角色不存在: id=" + charId);
            return;
        }
        Character chr = characterLoader.fromData(dbChar);
        MapleMap map = mapManager.getMap(chr.getMap());
        chr.setMapObject(map);
        // 会话代际认领（事故报告阶段 B）：新连接认领 = 新代际；先移除地图/在线表里
        // 同 id 的旧 Character（防广播双发），再由 claim 覆盖会话登记。
        removeSupersededCharacter(map, chr);
        map.addCharacter(chr);
        players.add(chr);
        long generation = sessions.claim(chr.getId(), session);
        session.setAttr("sessionGeneration", generation);
        if (leaseService != null) {
            // 新认领：旧代际租约立即失效（SESSION_REPLACED）
            leaseService.onClaim(chr.getId(), session.sessionId(), generation);
        }
        // 生成缺失怪物（去重）+ 把现存怪广播给进入玩家并分配无主怪控制权
        spawnService.ensureSpawned(map);
        spawnService.onPlayerEnter(map, session, new org.gms.domain.game.lease.LeaseOwner(
                chr.getId(), session.sessionId(), generation));
        session.setAttr("character", chr);
        session.transition(SessionStage.IN_GAME);
        session.send(ChannelPacketFactory.charInfo(chr, channelId));
        if (eventPublisher != null) {
            eventPublisher.playerOnline(chr);
        }
        LOG.info("玩家进图: {} (id={}) 地图={}", chr.getName(), chr.getId(), map.getMapId());
    }

    /** 移除地图/在线表里同 id 的非自身旧 Character（重复登录，防广播双发；旧代际断链迟到清理由 compare-and-remove 短路）。 */
    private void removeSupersededCharacter(MapleMap map, Character newChr) {
        for (var c : java.util.List.copyOf(map.characters())) {
            if (c.getId() == newChr.getId() && c != newChr) {
                map.removeCharacter(c);
                players.remove((Character) c);
            }
        }
    }
}
