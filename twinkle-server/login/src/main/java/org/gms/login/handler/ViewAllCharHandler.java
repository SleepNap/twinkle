package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Account;
import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.login.LoginPacketFactory;
import org.gms.login.LoginService;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

import java.util.List;
import java.util.Map;

/**
 * 查看所有角色处理（RecvOpcode.VIEW_ALL_CHAR 0x0D，v83 选角界面的"查看所有角色"）。
 *
 * <p>包结构：{@code byte worldCount + byte unknown}（M1 简化，客户端进此界面发本包）。
 * 回包两段：先 {@code showAllCharacter}（总览头：world 数 + 角色总数），
 * 再逐 world 发 {@code showAllCharacterInfo}（每个 world 的角色列表，viewall 布局）。
 * M1 单世界（world=0）。思路参考 BeiDou ViewAllCharHandler，实现自研。
 */
@Log4j2
public final class ViewAllCharHandler implements PacketHandler {


    private final LoginService loginService;

    public ViewAllCharHandler(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED && session.stage() != SessionStage.CHARLIST) {
            session.close("阶段外收到查看所有角色");
            return;
        }
        Account account = session.getAttr("account");
        if (account == null) {
            session.close("未登录收到查看所有角色");
            return;
        }
        // M1 单世界：查 world=0 全部角色
        List<Character> characters = loginService.charactersFor(account.getId(), 0);
        Map<Long, List<InventoryItemEntity>> equippedByChar = new java.util.HashMap<>();
        for (Character c : characters) {
            equippedByChar.put(c.getId(), loginService.equippedItems(c.getId()));
        }

        int totalWorlds = 1;
        int totalChrs = characters.size();
        log.info("查看所有角色: 账号={}, world=0, 角色数={}", account.getName(), totalChrs);
        session.send(LoginPacketFactory.showAllCharacter(totalWorlds, totalChrs));
        session.send(LoginPacketFactory.showAllCharacterInfo(0, characters, equippedByChar));
    }
}
