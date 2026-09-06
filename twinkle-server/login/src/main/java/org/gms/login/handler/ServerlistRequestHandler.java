package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.login.LoginPacketFactory;
import org.gms.login.ChannelSelectionService;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 服务器列表请求处理（RecvOpcode.SERVERLIST_REQUEST）。
 *
 * <p>从 coordinator 读取当前可用频道，按真实稳定 ID 编码后发送列表与结束标记。
 */
@Log4j2
public final class ServerlistRequestHandler implements PacketHandler {


    private final String serverName;
    private final int worldId;
    private final ChannelSelectionService channels;

    public ServerlistRequestHandler(String serverName, int worldId, ChannelSelectionService channels) {
        this.serverName = serverName;
        this.worldId = worldId;
        this.channels = channels;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED) {
            session.close(I18n.message("error.server_list.outside_stage"));
            return;
        }
        log.info(I18n.message("log.server_list.sent"), serverName);
        java.util.List<LoginPacketFactory.ServerChannel> entries = channels.availableChannels().stream()
                .map(channel -> new LoginPacketFactory.ServerChannel(
                        channel.channelId(), channel.onlineCount()))
                .toList();
        session.send(LoginPacketFactory.serverList(worldId, serverName, entries));
        session.send(LoginPacketFactory.endOfServerList());
    }
}
