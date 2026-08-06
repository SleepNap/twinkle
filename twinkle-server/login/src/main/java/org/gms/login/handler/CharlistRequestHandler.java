package org.gms.login.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.login.LoginPacketFactory;
import org.gms.login.LoginService;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.List;

/**
 * 角色列表请求处理（RecvOpcode.CHARLIST_REQUEST）。
 *
 * <p>包结构：{@code skip 1 + byte serverId}。回 {@code CHARLIST}（选角列表）。
 */
public final class CharlistRequestHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(CharlistRequestHandler.class);

    private final LoginService loginService;

    public CharlistRequestHandler(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED) {
            session.close("阶段外收到角色列表请求");
            return;
        }
        packet.skip(1);
        int serverId = packet.readByte();
        Account account = session.getAttr("account");
        List<Character> characters = loginService.charactersFor(account.getId(), serverId);

        session.setAttr("characters", characters);
        session.transition(SessionStage.CHARLIST);
        LOG.info("发送角色列表: 账号={}, 角色数={}", account.getName(), characters.size());
        session.send(LoginPacketFactory.charList(characters, serverId, 0));
    }
}
