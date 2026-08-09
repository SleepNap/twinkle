package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.login.LoginPacketFactory;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 服务器列表请求处理（RecvOpcode.SERVERLIST_REQUEST）。
 *
 * <p>M1 单世界单频道：回一条 {@code SERVERLIST} + 结束标记。
 */
@Log4j2
public final class ServerlistRequestHandler implements PacketHandler {


    private final String serverName;

    public ServerlistRequestHandler(String serverName) {
        this.serverName = serverName;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED) {
            session.close("阶段外收到服务器列表请求");
            return;
        }
        log.info("发送服务器列表: {}", serverName);
        session.send(LoginPacketFactory.serverList(0, serverName));
        session.send(LoginPacketFactory.endOfServerList());
    }
}
