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
 * 投影为内存态角色（CharacterLoader）→ 注册频道在线表 → 放入目标地图 →
 * 回 getCharInfo（SET_FIELD，客户端据此刻画角色并进入地图）。
 */
public final class PlayerLoggedinHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(PlayerLoggedinHandler.class);

    private final CharacterRepository characterRepo;
    private final CharacterLoader characterLoader;
    private final ChannelMapManager mapManager;
    private final PlayerStorage players;
    private final int channelId;

    public PlayerLoggedinHandler(CharacterRepository characterRepo, CharacterLoader characterLoader,
                                 ChannelMapManager mapManager, PlayerStorage players, int channelId) {
        this.characterRepo = characterRepo;
        this.characterLoader = characterLoader;
        this.mapManager = mapManager;
        this.players = players;
        this.channelId = channelId;
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
        map.addCharacter(chr);
        players.add(chr);
        session.setAttr("character", chr);
        session.transition(SessionStage.IN_GAME);
        session.send(ChannelPacketFactory.charInfo(chr, channelId));
        LOG.info("玩家进图: {} (id={}) 地图={}", chr.getName(), chr.getId(), map.getMapId());
    }
}
