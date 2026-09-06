package org.gms.bootstrap;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.i18n.I18n;
import org.gms.login.handler.LoginHandlerRegistrar;
import org.gms.net.netty.LoginServer;
import org.gms.net.packet.HandlerRegistry;
import org.gms.role.ManagementProcessCondition;


/**
 * 启动期网络装配（架构 bootstrap：Netty 登录服 + 登录 handler 注册，M1 链路）。
 *
 * <p>用 {@code @Context} + 构造注入强制启动装配：context 创建时即注册
 * login 模块 handler 并启动 {@link LoginServer}，
 * 不依赖 {@code ServerStartupEvent}（无 HTTP server 时该事件不发布）。
 *
 * <p>HTTP 与游戏 Netty 隔离 EventLoop（红线 4）：LoginServer 用独立
 * {@link io.netty.channel.MultiThreadIoEventLoopGroup}，不共享 Micronaut HTTP 的 EventLoop。
 *
 * <p>登录服是管理进程专属（single 全内嵌；split 下仅 coordinator 角色装配，
 * 频道进程不启登录服）。
 */
@Singleton
@Context
@Requires(condition = ManagementProcessCondition.class)
@Log4j2
public final class NetworkServerInitializer {


    public NetworkServerInitializer(HandlerRegistry registry,
                                    LoginHandlerRegistrar loginHandlers,
                                    LoginServer loginServer,
                                    @Property(name = "twinkle.net.login.port", defaultValue = "8484") int port,
                                    @Property(name = "twinkle.server.name", defaultValue = "twinkle") String serverName) {
        loginHandlers.register(registry, serverName);
        loginServer.start(port);
        log.info(I18n.message("log.bootstrap.login_started"), loginServer.boundPort());
    }

}
