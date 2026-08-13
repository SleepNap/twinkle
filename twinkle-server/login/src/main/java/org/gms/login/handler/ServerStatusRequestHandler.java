package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.login.LoginPacketFactory;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 服务器状态请求处理（RecvOpcode.SERVERSTATUS_REQUEST 0x06，v83 登录前置可选）。
 *
 * <p>部分客户端进服务器列表前探测世界负载。包结构：{@code short world}。
 * 回 {@code SERVERSTATUS}（写 short 状态；M1 单世界恒 0 = 正常，思路参考北斗）。
 */
@Log4j2
public final class ServerStatusRequestHandler implements PacketHandler {



    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED) {
            session.close(I18n.message("error.server_status.outside_stage"));
            return;
        }
        packet.readShort(); // world id
        session.send(LoginPacketFactory.serverStatus(0));
    }
}
