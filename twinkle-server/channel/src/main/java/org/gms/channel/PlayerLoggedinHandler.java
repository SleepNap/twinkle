package org.gms.channel;

import java.util.List;
import org.gms.channel.admin.ChannelEventPublisher;
import org.gms.domain.game.lease.LeaseOwner;
import org.gms.domain.game.lease.ControllerLeaseService;
import lombok.extern.log4j.Log4j2;
import org.gms.persistence.repo.PlayerCharacterRepository;
import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.concurrent.ThreadManager;
import org.gms.channel.persist.CharacterSaveQueue;
import java.util.concurrent.CompletableFuture;

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
    private final ControllerLeaseService leaseService;
    private final int channelId;
    private final ThreadManager background;
    private final CharacterSaveQueue saves;
    private final ChannelEventPublisher eventPublisher;

    public PlayerLoggedinHandler(PlayerCharacterRepository characterRepo, PlayerCharacterAssembler characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players,
                                 PlayerSessionRegistry sessions, MonsterSpawnService spawnService, int channelId) {
        this(characterRepo, characterLoader, mapManager, players, sessions, spawnService, channelId, null, null);
    }

    public PlayerLoggedinHandler(PlayerCharacterRepository characterRepo, PlayerCharacterAssembler characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players,
                                 PlayerSessionRegistry sessions, MonsterSpawnService spawnService, int channelId,
                                 ChannelEventPublisher eventPublisher) {
        this(characterRepo, characterLoader, mapManager, players, sessions, spawnService, channelId, eventPublisher, null);
    }

    public PlayerLoggedinHandler(PlayerCharacterRepository characterRepo, PlayerCharacterAssembler characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players,
                                 PlayerSessionRegistry sessions, MonsterSpawnService spawnService, int channelId,
                                 ChannelEventPublisher eventPublisher,
                                 ControllerLeaseService leaseService) {
        this(characterRepo, characterLoader, mapManager, players, sessions, spawnService, channelId,
                eventPublisher, leaseService, null, null);
    }

    public PlayerLoggedinHandler(PlayerCharacterRepository characterRepo, PlayerCharacterAssembler characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players, PlayerSessionRegistry sessions,
                                 MonsterSpawnService spawnService, int channelId,
                                 ChannelEventPublisher eventPublisher,
                                 ControllerLeaseService leaseService,
                                 ThreadManager background, CharacterSaveQueue saves) {
        this.characterRepo = characterRepo;
        this.characterLoader = characterLoader;
        this.mapManager = mapManager;
        this.players = players;
        this.sessions = sessions;
        this.spawnService = spawnService;
        this.channelId = channelId;
        this.eventPublisher = eventPublisher;
        this.leaseService = leaseService;
        this.background = background;
        this.saves = saves;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.LOGIN) {
            session.close(I18n.message("error.player_login.outside_stage"));
            return;
        }
        long charId = packet.readInt();
        if (sessions.execution() != null) { loadAsync(session, charId); return; }
        var dbChar = characterRepo.findById(charId).orElse(null);
        if (dbChar == null) {
            session.close(I18n.message("error.player_login.character_not_found", charId));
            return;
        }
        PlayerCharacter chr = characterLoader.fromData(dbChar);
        PacketSession previous = sessions.get(charId);
        try {
            enter(session, chr);
            if (previous != null && previous != session) previous.close(I18n.message("error.player_login.replaced"));
        } catch (RuntimeException | Error error) {
            abortLogin(session, previous, charId, null, error);
        }
    }

    private void enter(PacketSession session, PlayerCharacter chr) {
        MapleMap map = mapManager.getMap(chr.getMap());
        chr.setMapObject(map);
        var portal = map.getPortal(chr.getSpawnPoint());
        if (portal != null) { chr.setX(portal.getX()); chr.setY(portal.getY()); }
        // 编码失败必须发生在替换旧会话之前，避免坏档把仍可用的连接一起挤掉。
        var initialField = ChannelPacketFactory.charInfo(chr, channelId);
        session.setAttr("character", chr);
        long generation = sessions.claim(chr.getId(), session);
        session.setAttr("sessionGeneration", generation);
        removeSupersededCharacter(map, chr);
        players.add(chr);
        map.addCharacter(chr);
        if (leaseService != null) {
            // 新认领：旧代际租约立即失效（SESSION_REPLACED）
            leaseService.onClaim(chr.getId(), session.sessionId(), generation);
        }
        session.setAttr("mapTransition", true);
        session.setAttr("mapVisibilityReady", false);
        session.transition(SessionStage.IN_GAME);
        session.send(initialField);
        // 地图对象必须在 SET_FIELD 之后发送，否则客户端尚未创建场景。
        map.npcs().forEach(npc -> session.send(GameplayPackets.npc(npc)));
        spawnService.ensureSpawned(map);
        spawnService.onPlayerEnter(map, session, new LeaseOwner(
                chr.getId(), session.sessionId(), generation));
        Runnable completed = session.getAttr("afterLogin");
        session.setAttr("afterLogin", null);
        if (completed != null) completed.run();
        if (eventPublisher != null) eventPublisher.playerOnline(chr);
        log.info(I18n.message("log.player_login.entered_map"), chr.getName(), chr.getId(), map.getMapId());
    }

    private void loadAsync(PacketSession session, long characterId) {
        if (background == null || saves == null) throw new IllegalStateException(I18n.message("error.player_login.background_missing"));
        if (session.getAttr("stateTransfer") != null) return;
        Object token = new Object();
        PacketSession previous = sessions.get(characterId);
        PlayerCharacter old = previous == null ? null : previous.getAttr("character");
        if (previous != null && previous.getAttr("stateTransfer") != null
                || !sessions.beginLogin(characterId, token)) {
            session.close(I18n.message("error.player_login.transferring"));
            return;
        }
        session.setAttr("stateTransfer", token);
        try {
            if (previous != null) {
                Runnable cancel = previous.getAttr("cancelTrade");
                if (cancel != null) cancel.run();
                NpcTalkHandler.closeConversation(previous);
                previous.setAttr("stateTransfer", token);
            }
            CompletableFuture<Void> stored = old == null ? saves.awaitStored(characterId) : saves.saveAsync(old);
            stored.thenApplyAsync(ignored -> {
                var record = characterRepo.findById(characterId).orElseThrow(() -> new IllegalStateException(I18n.message("error.player_login.character_not_found", characterId)));
                return characterLoader.fromData(record);
            }, background).whenComplete((character, error) -> {
                if (sessions.execution().isClosed()) return;
                sessions.execution().execute(() -> {
                    try {
                        if (session.getAttr("stateTransfer") != token) return;
                        if (error != null || Boolean.TRUE.equals(session.getAttr("transportClosed"))) {
                            abortLogin(session, previous, characterId, token, error);
                            return;
                        }
                        session.setAttr("stateTransfer", null);
                        enter(session, character);
                        if (previous != null) previous.close(I18n.message("error.player_login.replaced"));
                    } catch (RuntimeException | Error failure) {
                        abortLogin(session, previous, characterId, token, failure);
                    } finally {
                        sessions.endLogin(characterId, token);
                    }
                });
            });
        } catch (RuntimeException | Error error) {
            try { abortLogin(session, previous, characterId, token, error); }
            finally { sessions.endLogin(characterId, token); }
        }
    }

    /** 失败候选不得进入断线存档；已提交认领则清理新代，尚未认领则恢复旧连接。 */
    private void abortLogin(PacketSession session, PacketSession previous, long characterId, Object token, Throwable error) {
        PlayerCharacter candidate = session.getAttr("character");
        if (sessions.get(characterId) == session) {
            sessions.unregister(characterId, session);
            Long generation = session.getAttr("sessionGeneration");
            if (leaseService != null && generation != null)
                leaseService.onDisconnect(characterId, session.sessionId(), generation);
        }
        if (candidate != null) {
            players.remove(candidate);
            if (candidate.getMapObject() != null) candidate.getMapObject().removeCharacter(candidate);
        }
        session.setAttr("character", null);
        session.setAttr("afterLogin", null);
        session.setAttr("stateTransfer", null);
        if (previous != null && previous != session) {
            if (sessions.get(characterId) == previous) {
                if (previous.getAttr("stateTransfer") == token) previous.setAttr("stateTransfer", null);
            } else {
                previous.close(I18n.message("error.player_login.replaced"));
            }
        }
        session.close(I18n.message("error.player_login.load_failed"));
        if (error != null) log.error(I18n.message("log.player_login.failed"), characterId, error);
    }

    /** 移除地图/在线表里同 id 的非自身旧 PlayerCharacter（重复登录，防广播双发；旧代际断链迟到清理由 compare-and-remove 短路）。 */
    private void removeSupersededCharacter(MapleMap map, PlayerCharacter newChr) {
        for (var c : List.copyOf(map.characters())) {
            if (c.getId() == newChr.getId() && c != newChr) {
                map.removeCharacter(c);
                players.remove((PlayerCharacter) c);
            }
        }
    }
}
