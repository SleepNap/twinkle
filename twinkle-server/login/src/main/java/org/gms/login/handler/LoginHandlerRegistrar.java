package org.gms.login.handler;

import jakarta.inject.Singleton;
import org.gms.data.repo.AccountRepository;
import org.gms.login.LoginService;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.HandlerRegistry;

/**
 * login 模块 handler 装配（把登录/选角处理器注册进 HandlerRegistry，贡献点版本化红线 13）。
 *
 * <p>bootstrap 装配时调用 {@link #register}。服务器名经配置注入（bootstrap 传）。
 */
@Singleton
public final class LoginHandlerRegistrar {

    private final LoginService loginService;
    private final AccountRepository accountRepository;

    public LoginHandlerRegistrar(LoginService loginService, AccountRepository accountRepository) {
        this.loginService = loginService;
        this.accountRepository = accountRepository;
    }

    /**
     * 注册 M1 登录链路 handler。
     *
     * @param registry    目标注册表
     * @param serverName  服务器列表展示名（如 "twinkle"）
     * @param channelIp   频道服 IP（选角后 SERVER_IP 下发）
     * @param channelPort 频道服端口
     */
    public void register(HandlerRegistry registry, String serverName, byte[] channelIp, int channelPort) {
        registry.register(RecvOpcode.LOGIN_PASSWORD, new LoginPasswordHandler(loginService));
        registry.register(RecvOpcode.ACCEPT_TOS, new AcceptToSHandler(accountRepository));
        registry.register(RecvOpcode.SET_GENDER, new SetGenderHandler(accountRepository));
        registry.register(RecvOpcode.SERVERSTATUS_REQUEST, new ServerStatusRequestHandler());
        registry.register(RecvOpcode.SET_HPMPALERT, new SetHpMpAlertHandler());
        registry.register(RecvOpcode.SERVERLIST_REQUEST, new ServerlistRequestHandler(serverName));
        registry.register(RecvOpcode.CHARLIST_REQUEST, new CharlistRequestHandler(loginService));
        registry.register(RecvOpcode.CHECK_CHAR_NAME, new CheckCharNameHandler(loginService));
        registry.register(RecvOpcode.CREATE_CHAR, new CreateCharHandler(loginService));
        registry.register(RecvOpcode.VIEW_ALL_CHAR, new ViewAllCharHandler(loginService));
        registry.register(RecvOpcode.PICK_ALL_CHAR, new PickAllCharHandler(loginService, channelIp, channelPort));
        registry.register(RecvOpcode.CHAR_SELECT, new CharSelectHandler(channelIp, channelPort));
    }
}
