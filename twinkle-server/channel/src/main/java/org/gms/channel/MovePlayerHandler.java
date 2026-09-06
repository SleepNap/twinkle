package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.v83.V83Movement;
import org.gms.replaceable.MovementSystem;

/** 玩家移动：验证整个片段流、提交位置和姿态，再向同地图其他有效会话转发。 */
public final class MovePlayerHandler implements PacketHandler {
    private final MovementSystem movementSystem;
    private final PlayerSessionRegistry sessions;

    public MovePlayerHandler(MovementSystem movementSystem, PlayerSessionRegistry sessions) {
        this.movementSystem = movementSystem; this.sessions = sessions;
    }

    @Override public void handle(PacketSession session, InPacket packet) {
        PlayerCharacter character = GameplaySession.character(session);
        if (!GameplaySession.canAct(session, character) || sessions.get(character.getId()) != session
                || packet.available() < 10) return;
        packet.skip(9);
        V83Movement movement = V83Movement.read(packet);
        if (movement == null) return;
        synchronized (character) {
            if (!GameplaySession.canAct(session, character) || sessions.get(character.getId()) != session) return;
            if (!movementSystem.applyMotion(character, movement.x(), movement.y(), movement.stance(), movement.foothold())) return;
            sessions.broadcastToMap(character.getMapObject(), GamePacketFactory.movePlayer(character.getId(), movement.bytes()),
                    character.getId());
        }
    }
}
