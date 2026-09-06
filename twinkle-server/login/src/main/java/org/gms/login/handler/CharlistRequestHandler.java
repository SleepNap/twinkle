package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.persistence.entity.GameAccountRecord;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.login.LoginPacketFactory;
import org.gms.login.LoginService;
import org.gms.login.ChannelSelectionService;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.List;

/**
 * 角色列表请求处理（RecvOpcode.CHARLIST_REQUEST）。
 *
 * <p>包结构：{@code byte worldId + byte wireChannelId}。频道 ID 是显式值而非列表位置。
 */
@Log4j2
public final class CharlistRequestHandler implements PacketHandler {


    private final LoginService loginService;
    private final ChannelSelectionService channels;
    private final int worldId;

    public CharlistRequestHandler(LoginService loginService, ChannelSelectionService channels, int worldId) {
        this.loginService = loginService;
        this.channels = channels;
        this.worldId = worldId;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED) {
            session.close(I18n.message("error.char_list.outside_stage"));
            return;
        }
        if (packet.available() < 2) {
            session.close("Invalid world/channel selection packet");
            return;
        }
        int requestedWorldId = packet.readByte() & 0xFF;
        if (requestedWorldId != worldId) {
            session.close("Selected world is unavailable: " + requestedWorldId);
            return;
        }
        int channelId = ChannelSelectionService.fromWireId(packet.readByte() & 0xFF);
        if (channels.channel(channelId).isEmpty()) {
            session.close("Selected channel is unavailable: " + channelId);
            return;
        }
        GameAccountRecord account = session.getAttr("account");
        List<PlayerCharacterRecord> characters = loginService.charactersFor(account.getId(), requestedWorldId);

        // 每个角色查已穿戴装备（选角列表外观编码用；无装备 = 内衣）
        java.util.Map<Long, java.util.List<org.gms.persistence.entity.InventoryItemEntity>> equippedByChar =
                new java.util.HashMap<>();
        for (PlayerCharacterRecord c : characters) {
            equippedByChar.put(c.getId(), loginService.equippedItems(c.getId()));
        }

        session.setAttr("characters", characters);
        session.setAttr(ChannelSelectionService.SESSION_WORLD_ID, requestedWorldId);
        session.setAttr(ChannelSelectionService.SESSION_CHANNEL_ID, channelId);
        session.transition(SessionStage.CHARLIST);
        log.info(I18n.message("log.char_list.sent"), account.getName(), characters.size());
        session.send(LoginPacketFactory.charList(characters, requestedWorldId, 0, equippedByChar));
    }
}
