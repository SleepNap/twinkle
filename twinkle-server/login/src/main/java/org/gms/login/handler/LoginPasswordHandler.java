package org.gms.login.handler;

import lombok.extern.log4j.Log4j2;
import org.gms.data.entity.Account;
import org.gms.login.LoginPacketFactory;
import org.gms.login.LoginService;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.PacketHandler;
import org.gms.net.packet.PacketSession;
import org.gms.net.packet.SessionStage;

/**
 * 登录包处理（RecvOpcode.LOGIN_PASSWORD）。
 *
 * <p>包结构：{@code string 账号 + string 密码 + skip 6 + 4 字节 HWID}。
 * 校验通过 → 发 LOGIN_STATUS 成功包并推进到 AUTHED；失败 → 发对应错误码。
 */
@Log4j2
public final class LoginPasswordHandler implements PacketHandler {


    private final LoginService loginService;

    public LoginPasswordHandler(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public void handle(PacketSession session, InPacket packet) {
        if (session.stage() != SessionStage.LOGIN) {
            session.close("登录阶段外收到登录包");
            return;
        }
        String login = packet.readString();
        String password = packet.readString();
        packet.skip(6);
        packet.readBytes(4); // HWID（M1 不校验）

        LoginService.LoginResult result = loginService.authenticate(login, password);
        if (result.errorCode() != 0) {
            log.info("登录失败: {} code={}", login, result.errorCode());
            session.send(LoginPacketFactory.loginStatusFailed(result.errorCode()));
            return;
        }
        Account account = result.account();
        session.setAttr("account", account);
        session.transition(SessionStage.AUTHED);
        log.info("登录成功: {} (id={})", account.getName(), account.getId());
        // v83 登录前置：tos=0（未接受服务条款）→ LOGIN_STATUS reason 23，客户端弹条款界面，
        // 发 ACCEPT_TOS 后本 handler 重发成功。gender=10（未设性别）客户端自行发 SET_GENDER。
        if (account.getTos() == 0) {
            log.info("账号 {} 未接受服务条款，等待 ACCEPT_TOS", account.getName());
            session.send(LoginPacketFactory.loginStatusFailed(23));
            return;
        }
        session.send(LoginPacketFactory.loginStatusSuccess(
                account.getId(), account.getGender(), account.getName()));
    }
}
