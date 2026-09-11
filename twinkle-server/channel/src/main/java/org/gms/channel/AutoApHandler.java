package org.gms.channel;

import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.domain.game.logic.ProgressionSystem;

import java.util.HashMap;
import java.util.Map;

/** 客户端自动分配的两组 AP 增量必须整体通过校验，再统一扣点。 */
public final class AutoApHandler implements PacketHandler {
    private final ProgressionSystem system;
    public AutoApHandler(ProgressionSystem system) { this.system = system; }
    @Override public void handle(PacketSession session, InPacket packet) {
        try {
            var character = GameplaySession.character(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 24) return;
            packet.skip(8);
            Map<Integer, Integer> increments = new HashMap<>();
            for (int index = 0; index < 2; index++) {
                int mask = packet.readInt(), count = packet.readInt();
                if (count < 0 || count > Short.MAX_VALUE) return;
                if (count > 0) increments.merge(mask, count, Integer::sum);
            }
            if (!system.allocateAp(character, increments)) return;
            session.send(GameplayPackets.stats(Map.of(GameplayPackets.STR, (int) character.getStrStat(),
                    GameplayPackets.DEX, (int) character.getDexStat(), GameplayPackets.INT, (int) character.getIntStat(),
                    GameplayPackets.LUK, (int) character.getLukStat(), GameplayPackets.AP, character.getAp())));
        } finally { session.send(GameplayPackets.enableActions()); }
    }
}
