package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.script.host.Em;
import org.gms.net.packet.PacketSession;

/**
 * 地图脚本宿主（{@link Em} 的频道侧实现，对应 {@code scripts/field/<onUserEnter>.js}）。
 *
 * <p>本期只实现契约里已有的两个能力：地图限时记录与同图广播。限时目前只保存在宿主内并由
 * {@link #mapTimer()} 暴露给测试与后续 Tick 接入，尚未下发客户端时钟包；广播发给同图所有会话。
 */
public final class FieldScriptHost implements Em {

    private final PacketSession session;
    private final PlayerCharacter character;
    private final PlayerSessionRegistry sessions;
    private int mapTimer;

    public FieldScriptHost(PacketSession session, PlayerCharacter character, PlayerSessionRegistry sessions) {
        this.session = session;
        this.character = character;
        this.sessions = sessions;
    }

    @Override
    public void setMapTimer(int seconds) {
        this.mapTimer = Math.max(0, seconds);
    }

    /** 脚本设置的地图限时秒数；0 表示不限时或已取消。 */
    public int mapTimer() {
        return mapTimer;
    }

    @Override
    public void broadcastMessage(String message) {
        if (message == null || message.isBlank()) return;
        MapleMap map = character.getMapObject();
        if (map == null) return;
        if (sessions == null) {
            session.send(ChannelPacketFactory.serverNotice(4, message));
            return;
        }
        for (PacketSession other : sessions.all()) {
            PlayerCharacter member = other.getAttr("character");
            if (member != null && member.getMapObject() == map) {
                other.send(ChannelPacketFactory.serverNotice(4, message));
            }
        }
    }
}
