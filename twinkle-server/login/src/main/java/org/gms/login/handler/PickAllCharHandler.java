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
 * 查看所有角色界面选角处理（RecvOpcode.PICK_ALL_CHAR 0x0E）。
 *
 * <p>包结构：{@code int charId + int worldId + string macs + string hostString}。
 * 校验角色属于当前账号（防越权），成功后回 {@code SERVER_IP} 让客户端连频道服
 * （未显式选频道时使用 coordinator 中 ID 最小的可用频道）。思路参考 BeiDou
 * ViewAllCharSelectedHandler，实现自研。
 */
@Log4j2
public final class PickAllCharHandler implements PacketHandler {


    private final LoginService loginService;
    private final ChannelSelectionService channels;
    private final int worldId;

    public PickAllCharHandler(LoginService loginService, ChannelSelectionService channels, int worldId) {
        this.loginService = loginService;
        this.channels = channels;
        this.worldId = worldId;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED && session.stage() != SessionStage.CHARLIST) {
            session.close(I18n.message("error.pick_all_char.outside_stage"));
            return;
        }
        GameAccountRecord account = session.getAttr("account");
        if (account == null) {
            session.close(I18n.message("error.pick_all_char.not_logged_in"));
            return;
        }
        long charId = packet.readInt();
        int requestedWorldId = packet.readInt();
        if (requestedWorldId != worldId) {
            session.close("Selected world is unavailable: " + requestedWorldId);
            return;
        }
        packet.readString();        // macs
        packet.readString();        // hostString

        List<PlayerCharacterRecord> characters = loginService.charactersFor(account.getId(), requestedWorldId);
        PlayerCharacterRecord selected = characters.stream()
                .filter(c -> c.getId() != null && c.getId() == charId)
                .findFirst()
                .orElse(null);
        if (selected == null) {
            session.close(I18n.message("error.pick_all_char.forbidden", charId));
            return;
        }

        session.setAttr("selectedChar", selected);
        ChannelSelectionService.Endpoint endpoint = channels.firstAvailable().orElse(null);
        if (endpoint == null) {
            session.close("No channel is available");
            return;
        }
        session.transition(SessionStage.SELECTED);
        log.info(I18n.message("log.pick_all_char.selected"), selected.getName(), charId);
        session.send(LoginPacketFactory.serverIp(endpoint.ipv4(), endpoint.port(), (int) charId));
    }
}
