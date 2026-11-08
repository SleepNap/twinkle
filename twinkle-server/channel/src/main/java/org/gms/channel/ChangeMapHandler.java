package org.gms.channel;

import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;

/** 收包字段事实参考北斗；普通门、特殊门与复活共享 Twinkle 的服务端传送用例。 */
public final class ChangeMapHandler implements PacketHandler {
    private final MapTransitionService transitions;
    private final boolean namedPortal;

    public ChangeMapHandler(MapTransitionService transitions, boolean namedPortal) {
        this.transitions = transitions;
        this.namedPortal = namedPortal;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        boolean accepted = false;
        if (packet.available() >= (namedPortal ? 3 : 7)) {
            packet.readByte();
            if (namedPortal) accepted = transitions.usePortal(session, packet.readString(), -1);
            else {
                int mapId = packet.readInt();
                String portalName = packet.readString();
                // v83 后续两个字节：未用标志 + 复活轮标志；老客户端没带时按普通复活处理。
                boolean wheel = false;
                if (packet.available() >= 2) {
                    packet.skip(1);
                    wheel = packet.readByte() > 0;
                }
                var character = GameplaySession.character(session);
                accepted = character != null && character.getHp() <= 0
                        ? transitions.revive(session, wheel) : transitions.usePortal(session, portalName, mapId);
            }
        }
        if (!accepted) session.send(GameplayPackets.enableActions());
    }
}
