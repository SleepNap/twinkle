package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;
/** 普通地图聊天处理器。 */
public final class GeneralChatHandler implements PacketHandler {

    private static final int MAX_CHAT_LENGTH = 500;

    private final PlayerSessionRegistry sessions;

    public GeneralChatHandler(PlayerSessionRegistry sessions) {
        this.sessions = sessions;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.IN_GAME) {
            session.close(I18n.message("error.chat.outside_stage"));
            return;
        }
        Character character = session.getAttr("character");
        if (character == null) {
            session.close(I18n.message("error.chat.not_in_map"));
            return;
        }
        if (packet.available() < 2) {
            return;
        }

        String text;
        try {
            text = packet.readString().trim();
        } catch (RuntimeException e) {
            session.close(I18n.message("error.chat.invalid_packet"));
            return;
        }
        int show = packet.available() > 0 ? packet.readByte() & 0xFF : 0;
        if (text.isEmpty() || text.length() > MAX_CHAT_LENGTH) {
            sendNotice(session, I18n.message("game.chat.length_invalid", MAX_CHAT_LENGTH));
            return;
        }

        if (character.getMapObject() != null) {
            sessions.broadcastToMap(character.getMapObject(),
                    ChannelPacketFactory.chatText(character.getId(), false, text, show));
        }
    }

    private static void sendNotice(PacketSession session, String text) {
        session.send(ChannelPacketFactory.serverNotice(text));
    }
}
