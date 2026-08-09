package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Account;
import org.gms.data.repo.AccountRepository;
import org.gms.login.LoginPacketFactory;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 设置性别处理（RecvOpcode.SET_GENDER 0x08，v83 登录前置）。
 *
 * <p>账号 {@code gender=10}（未设置）时客户端弹选性别界面，发本包：
 * {@code byte confirmed(1=确认) + byte gender(0男/1女)}。服务端落库后重发
 * LOGIN_STATUS 成功（思路参考 BeiDou-Server 的 SetGenderHandler，实现自研）。
 */
@Log4j2
public final class SetGenderHandler implements PacketHandler {



    private final AccountRepository accountRepository;

    public SetGenderHandler(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.AUTHED) {
            session.close("阶段外收到设置性别");
            return;
        }
        Account account = session.getAttr("account");
        if (account == null) {
            session.close("未登录收到设置性别");
            return;
        }
        if (account.getGender() != 10) {
            // 性别已设置，客户端不应发本包（与北斗语义一致）
            return;
        }
        int confirmed = packet.readByte();
        if (confirmed != 1) {
            session.close("性别确认失败");
            return;
        }
        int gender = packet.readByte();
        account.setGender(gender);
        accountRepository.update(account);
        log.info("账号 {} 设置性别: {}", account.getName(), gender);
        session.send(LoginPacketFactory.loginStatusSuccess(
                account.getId(), account.getGender(), account.getName()));
    }
}
