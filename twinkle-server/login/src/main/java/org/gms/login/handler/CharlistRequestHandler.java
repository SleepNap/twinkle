package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
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
@Log4j2
public final class CharlistRequestHandler implements PacketHandler {


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

        // 每个角色查已穿戴装备（选角列表外观编码用；无装备 = 内衣）
        java.util.Map<Long, java.util.List<org.gms.data.entity.InventoryItemEntity>> equippedByChar =
                new java.util.HashMap<>();
        for (Character c : characters) {
            equippedByChar.put(c.getId(), loginService.equippedItems(c.getId()));
        }

        session.setAttr("characters", characters);
        session.transition(SessionStage.CHARLIST);
        log.info("发送角色列表: 账号={}, 角色数={}", account.getName(), characters.size());
        session.send(LoginPacketFactory.charList(characters, serverId, 0, equippedByChar));
    }
}
