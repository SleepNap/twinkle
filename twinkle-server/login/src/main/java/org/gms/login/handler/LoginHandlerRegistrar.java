package org.gms.login.handler;

import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Property;
import org.gms.persistence.repo.GameAccountRepository;
import org.gms.login.LoginService;
import org.gms.login.ChannelSelectionService;
import org.gms.net.opcodes.RecvOpcode;
import org.gms.net.packet.HandlerRegistry;
import org.gms.net.packet.v83.V83WorldId;

/**
 * login 模块 handler 装配（把登录/选角处理器注册进 HandlerRegistry，贡献点版本化红线 13）。
 *
 * <p>bootstrap 装配时调用 {@link #register}。服务器名经配置注入（bootstrap 传）。
 */
@Singleton
public final class LoginHandlerRegistrar {

    private final LoginService loginService;
    private final GameAccountRepository accountRepository;
    private final ChannelSelectionService channels;
    private final int worldId;

    public LoginHandlerRegistrar(LoginService loginService, GameAccountRepository accountRepository,
                                 ChannelSelectionService channels,
                                 @Property(name = "twinkle.net.world.id", defaultValue = "0") int worldId) {
        this.loginService = loginService;
        this.accountRepository = accountRepository;
        this.channels = channels;
        this.worldId = V83WorldId.validate(worldId);
    }

    /**
     * 注册 M1 登录链路 handler。
     *
     * @param registry    目标注册表
     * @param serverName  服务器列表展示名（如 "twinkle"）
     */
    public void register(HandlerRegistry registry, String serverName) {
        registry.register(RecvOpcode.LOGIN_PASSWORD, new LoginPasswordHandler(loginService));
        registry.register(RecvOpcode.ACCEPT_TOS, new AcceptToSHandler(accountRepository));
        registry.register(RecvOpcode.SET_GENDER, new SetGenderHandler(accountRepository));
        registry.register(RecvOpcode.SERVERSTATUS_REQUEST, new ServerStatusRequestHandler());
        registry.register(RecvOpcode.SET_HPMPALERT, new SetHpMpAlertHandler());
        registry.register(RecvOpcode.SERVERLIST_REQUEST, new ServerlistRequestHandler(serverName, worldId, channels));
        registry.register(RecvOpcode.CHARLIST_REQUEST, new CharlistRequestHandler(loginService, channels, worldId));
        registry.register(RecvOpcode.CHECK_CHAR_NAME, new CheckCharNameHandler(loginService));
        registry.register(RecvOpcode.CREATE_CHAR, new CreateCharHandler(loginService, worldId));
        registry.register(RecvOpcode.VIEW_ALL_CHAR, new ViewAllCharHandler(loginService, worldId));
        registry.register(RecvOpcode.PICK_ALL_CHAR, new PickAllCharHandler(loginService, channels, worldId));
        registry.register(RecvOpcode.CHAR_SELECT, new CharSelectHandler(channels));
    }
}
