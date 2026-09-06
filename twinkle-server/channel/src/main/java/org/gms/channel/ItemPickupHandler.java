package org.gms.channel;

import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;

/** 手动拾取只接受当前地图中的服务端掉落对象 ID，客户端坐标不作为距离真值。 */
public final class ItemPickupHandler implements PacketHandler {
    private final GroundDropService drops;
    public ItemPickupHandler(GroundDropService drops) { this.drops = drops; }
    @Override
    public void handle(PacketSession session, InPacket packet) {
        try {
            var character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 13) return;
            packet.skip(9);
            drops.pickup(character, packet.readInt());
        } finally { session.send(GameplayPackets.enableActions()); }
    }
}
