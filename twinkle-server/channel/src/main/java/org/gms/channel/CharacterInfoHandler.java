package org.gms.channel;

import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;

/** 双击角色详情，只读取本频道当前地图成员，不接受跨地图或旧连接对象查询。 */
public final class CharacterInfoHandler implements PacketHandler {
    private final PlayerSessionRegistry sessions;
    public CharacterInfoHandler(PlayerSessionRegistry sessions) { this.sessions = sessions; }

    @Override public void handle(PacketSession session, InPacket packet) {
        var source = GameplaySession.character(session);
        if (source == null || sessions.get(source.getId()) != session || source.getMapObject() == null
                || session.getAttr("mapTransition") != null || packet.available() < 8) return;
        packet.skip(4);
        PacketSession targetSession = sessions.get(packet.readInt());
        if (targetSession == null) return;
        var target = GameplaySession.character(targetSession);
        if (target != null && target.getMapObject() == source.getMapObject()
                && targetSession.getAttr("mapTransition") == null && source.getMapObject().characters().contains(target)) {
            session.send(PlayerUtilityPackets.characterInfo(target));
        }
    }
}
