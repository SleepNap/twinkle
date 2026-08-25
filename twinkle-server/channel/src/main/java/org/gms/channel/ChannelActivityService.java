package org.gms.channel;

import org.gms.channel.admin.ChannelEventPublisher;
import org.gms.channel.persist.CharacterSaveQueue;
import org.gms.domain.game.Character;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
import org.gms.service.intercoord.IntercoordService.PlayerActivity;

/**
 * 频道连接上的活动状态切换（北斗模式）。
 *
 * <p>进入商城/MTS 时 TCP 会话仍归本频道，故 {@link PlayerSessionRegistry} 不注销；只把角色从
 * {@link PlayerStorage} 和地图移除，并把大区 Presence 的活动状态改掉。真正断链仍走频道统一
 * DisconnectListener，届时才发布 PlayerOffline 并注销大区在线态。
 */
public final class ChannelActivityService {

    private final int worldId;
    private final int channelId;
    private final PlayerStorage players;
    private final PlayerSessionRegistry sessions;
    private final CharacterSaveQueue saveQueue;
    private final ChannelEventPublisher eventPublisher;

    public ChannelActivityService(int worldId, int channelId, PlayerStorage players,
                                  PlayerSessionRegistry sessions, CharacterSaveQueue saveQueue,
                                  ChannelEventPublisher eventPublisher) {
        this.worldId = worldId;
        this.channelId = channelId;
        this.players = players;
        this.sessions = sessions;
        this.saveQueue = saveQueue;
        this.eventPublisher = eventPublisher;
    }

    public boolean enterCashShop(PacketSession session) {
        return enterAwayActivity(session, PlayerActivity.CASH_SHOP, SessionStage.CASH_SHOP);
    }

    public boolean enterMts(PacketSession session) {
        return enterAwayActivity(session, PlayerActivity.MTS, SessionStage.MTS);
    }

    private boolean enterAwayActivity(PacketSession session, PlayerActivity activity, SessionStage stage) {
        if (session.stage() != SessionStage.IN_GAME) {
            return false;
        }
        Character chr = session.getAttr("character");
        if (chr == null || sessions.get(chr.getId()) != session) {
            return false;
        }

        // 先落最新角色态，再退出频道游戏域；商城/MTS 期间会话继续由本频道持有。
        saveQueue.flushCharacterSync(chr);
        if (chr.getMapObject() != null) {
            chr.getMapObject().removeCharacter(chr);
        }
        players.remove(chr);
        session.transition(stage);
        eventPublisher.playerActivity(chr.getId(), worldId, channelId, activity);
        return true;
    }
}
