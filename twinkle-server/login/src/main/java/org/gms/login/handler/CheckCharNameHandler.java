package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Account;
import org.gms.login.LoginPacketFactory;
import org.gms.login.LoginService;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 检查角色名处理（RecvOpcode.CHECK_CHAR_NAME 0x15，v83 建角前置）。
 *
 * <p>建角界面输入名字后客户端先发本包查重。包结构：{@code string name}。
 * 回 {@code CHAR_NAME_RESPONSE}（string name + byte 1=已占用/0=可用，思路参考
 * BeiDou CheckCharNameHandler）。名字不可用 / 未登录则直接断连（恶意包）。
 */
@Log4j2
public final class CheckCharNameHandler implements PacketHandler {


    private final LoginService loginService;

    public CheckCharNameHandler(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        // 建角发生在角色列表界面（CHARLIST 阶段）；登录态兜底用 AUTHED 兼容
        if (session.stage() != SessionStage.CHARLIST && session.stage() != SessionStage.AUTHED) {
            session.close(I18n.message("error.check_char_name.outside_stage"));
            return;
        }
        Account account = session.getAttr("account");
        if (account == null) {
            session.close(I18n.message("error.check_char_name.not_logged_in"));
            return;
        }
        String name = packet.readString();
        boolean available = loginService.isNameAvailable(name);
        log.info(I18n.message("log.check_char_name.checked"), account.getName(), name, available);
        session.send(LoginPacketFactory.charNameResponse(name, !available));
    }
}
