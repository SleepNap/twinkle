package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.persistence.entity.PlayerCharacterRecord;
import org.gms.login.LoginPacketFactory;
import org.gms.login.ChannelSelectionService;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.List;

/**
 * 选角处理（RecvOpcode.CHAR_SELECT）。
 *
 * <p>包结构：{@code int charId + string macs + string hostString}。
 * 校验角色属于当前账号（防越权），成功后回 {@code SERVER_IP} 让客户端连频道服
 * （地址按会话所选真实频道 ID 从 coordinator 动态解析）。
 */
@Log4j2
public final class CharSelectHandler implements PacketHandler {


    private final ChannelSelectionService channels;

    public CharSelectHandler(ChannelSelectionService channels) {
        this.channels = channels;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.CHARLIST) {
            session.close(I18n.message("error.char_select.outside_stage"));
            return;
        }
        long charId = packet.readInt();
        packet.readString(); // macs
        packet.readString(); // hostString

        List<PlayerCharacterRecord> characters = session.getAttr("characters");
        PlayerCharacterRecord selected = characters.stream()
                .filter(c -> c.getId() != null && c.getId() == charId)
                .findFirst()
                .orElse(null);
        if (selected == null) {
            session.close(I18n.message("error.char_select.forbidden", charId));
            return;
        }

        session.setAttr("selectedChar", selected);
        Integer channelId = session.getAttr(ChannelSelectionService.SESSION_CHANNEL_ID);
        ChannelSelectionService.Endpoint endpoint = channelId == null
                ? null : channels.channel(channelId).orElse(null);
        if (endpoint == null) {
            session.close("Selected channel is unavailable: " + channelId);
            return;
        }
        session.transition(SessionStage.SELECTED);
        log.info(I18n.message("log.char_select.selected"), selected.getName(), charId);
        session.send(LoginPacketFactory.serverIp(endpoint.ipv4(), endpoint.port(), (int) charId));
    }
}
