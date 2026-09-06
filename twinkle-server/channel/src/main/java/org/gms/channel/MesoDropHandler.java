package org.gms.channel;

import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;

/** 金币丢弃报文为客户端时间戳和金额；以服务端余额为准。布局核对 BeiDou-Server MesoDropHandler。 */
public final class MesoDropHandler implements PacketHandler {
    private final PlayerSessionRegistry sessions;
    private final GroundDropService drops;
    public MesoDropHandler(PlayerSessionRegistry sessions, GroundDropService drops) {
        this.sessions = sessions; this.drops = drops;
    }
    @Override public void handle(PacketSession session, InPacket packet) {
        try {
            var character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || sessions.get(character.getId()) != session
                    || packet.available() != 8) return;
            packet.skip(4); drops.dropMesos(character, packet.readInt());
        } finally { session.send(GameplayPackets.enableActions()); }
    }
}
