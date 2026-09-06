package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.wz.GameDataProvider;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketSession;
import org.gms.replaceable.AvatarSystem;

import java.time.Clock;

/** 表情、使用椅子、起身三个收包入口；仅支持基础表情和已持有的普通背包椅子。 */
public final class AvatarHandler {
    private final PlayerSessionRegistry sessions;
    private final AvatarSystem system;
    private final GameDataProvider data;
    private final Clock clock;

    public AvatarHandler(PlayerSessionRegistry sessions, AvatarSystem system, GameDataProvider data, Clock clock) {
        this.sessions = sessions; this.system = system; this.data = data; this.clock = clock;
    }

    public void express(PacketSession session, InPacket packet) {
        PlayerCharacter character = current(session);
        if (!GameplaySession.canAct(session, character) || packet.available() < 4) return;
        int expression = packet.readInt();
        if (system.express(character, expression)) sessions.broadcastToMap(character.getMapObject(),
                PlayerPresencePackets.expression(character.getId(), expression), character.getId());
    }

    public void sit(PacketSession session, InPacket packet) {
        try {
            PlayerCharacter character = current(session);
            if (!GameplaySession.canAct(session, character) || packet.available() < 4) return;
            int itemId = packet.readInt();
            if (data.item(itemId) == null || !system.sit(character, itemId, clock.millis())) return;
            sessions.broadcastToMap(character.getMapObject(), PlayerPresencePackets.chair(character.getId(), itemId),
                    character.getId());
        } finally { session.send(GameplayPackets.enableActions()); }
    }

    public void stand(PacketSession session, InPacket packet) {
        PlayerCharacter character = current(session);
        if (character == null || packet.available() < 2 || session.getAttr("mapTransition") != null) return;
        // 非负值属于地图固定座位，需要独立的 WZ 座位占用模型，当前明确拒绝。
        int seat = packet.readShort();
        if (seat < -1) return;
        clearChair(session, character);
    }

    public void refresh(PacketSession session) {
        PlayerCharacter character = current(session);
        if (character == null) return;
        int chair = character.getChairItemId();
        if (chair != 0 && (character.getHp() <= 0 || !character.ownsUsableItem(chair, (byte) 3, clock.millis()))) {
            clearChair(session, character);
        }
    }

    private void clearChair(PacketSession session, PlayerCharacter character) {
        if (!system.stand(character)) return;
        session.send(PlayerPresencePackets.cancelChair());
        if (character.getMapObject() != null) sessions.broadcastToMap(character.getMapObject(),
                PlayerPresencePackets.chair(character.getId(), 0), character.getId());
    }

    private PlayerCharacter current(PacketSession session) {
        PlayerCharacter character = GameplaySession.character(session);
        return character != null && sessions.get(character.getId()) == session ? character : null;
    }
}
