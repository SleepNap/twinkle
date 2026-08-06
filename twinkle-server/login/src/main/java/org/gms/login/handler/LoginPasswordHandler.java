package org.gms.login.handler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
public final class LoginPasswordHandler implements PacketHandler {

    private static final Logger LOG = LogManager.getLogger(LoginPasswordHandler.class);

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
            LOG.info("登录失败: {} code={}", login, result.errorCode());
            session.send(LoginPacketFactory.loginStatusFailed(result.errorCode()));
            return;
        }
        Account account = result.account();
        session.setAttr("account", account);
        session.transition(SessionStage.AUTHED);
        LOG.info("登录成功: {} (id={})", account.getName(), account.getId());
        session.send(LoginPacketFactory.loginStatusSuccess(
                account.getId(), account.getGender(), account.getName()));
    }
}
