package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/** 游戏写操作共用的会话与角色状态门槛。 */
public final class GameplaySession {
    private GameplaySession() { }

    public static PlayerCharacter character(PacketSession session) {
        return session.stage() == SessionStage.IN_GAME ? session.getAttr("character") : null;
    }

    public static boolean canAct(PacketSession session, PlayerCharacter character) {
        return character != null && character.getHp() > 0 && character.getMapObject() != null
                && session.getAttr("trade") == null && session.getAttr("mapTransition") == null;
    }

    public static boolean near(PlayerCharacter character, int x, int y, int distance) {
        long dx = (long) character.getX() - x;
        long dy = (long) character.getY() - y;
        return Math.abs(dx) <= distance && Math.abs(dy) <= distance
                && dx * dx + dy * dy <= (long) distance * distance;
    }
}
