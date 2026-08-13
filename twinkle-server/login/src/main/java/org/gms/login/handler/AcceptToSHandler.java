package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Account;
import org.gms.data.repo.AccountRepository;
import org.gms.login.LoginPacketFactory;
import org.gms.i18n.I18n;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 接受服务条款处理（RecvOpcode.ACCEPT_TOS 0x07，v83 登录前置）。
 *
 * <p>账号 {@code tos=0}（未接受条款）时客户端弹服务条款界面，发本包：
 * {@code byte 1}（确认）。服务端落库 tos=1 后重发 LOGIN_STATUS 成功
 * （思路参考 BeiDou-Server 的 AcceptToSHandler，实现自研）。
 */
@Log4j2
public final class AcceptToSHandler implements PacketHandler {



    private final AccountRepository accountRepository;

    public AcceptToSHandler(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED) {
            session.close(I18n.message("error.accept_tos.outside_stage"));
            return;
        }
        Account account = session.getAttr("account");
        if (account == null) {
            session.close(I18n.message("error.accept_tos.not_logged_in"));
            return;
        }
        if (packet.available() == 0 || packet.readByte() != 1) {
            session.close(I18n.message("error.accept_tos.confirm_failed"));
            return;
        }
        account.setTos(1);
        accountRepository.update(account);
        log.info(I18n.message("log.accept_tos.accepted"), account.getName());
        session.send(LoginPacketFactory.loginStatusSuccess(
                account.getId(), account.getGender(), account.getName()));
    }
}
