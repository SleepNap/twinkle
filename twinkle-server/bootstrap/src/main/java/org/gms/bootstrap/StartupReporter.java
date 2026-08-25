package org.gms.bootstrap;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.channel.ChannelServer;
import org.gms.i18n.I18n;
import org.gms.net.netty.LoginServer;

import java.util.Optional;

/** 在 HTTP 服务可用后输出唯一一条应用级启动完成摘要。 */
@Singleton
@Log4j2
public final class StartupReporter implements ApplicationEventListener<ServerStartupEvent> {

    private final String serverName;
    private final String profile;
    private final String role;
    private final Optional<LoginServer> loginServer;
    private final Optional<ChannelServer> channelServer;

    public StartupReporter(
            @Property(name = "twinkle.server.name", defaultValue = "twinkle") String serverName,
            @Property(name = "twinkle.profile", defaultValue = "single") String profile,
            @Property(name = "twinkle.role", defaultValue = "") String role,
            Optional<LoginServer> loginServer,
            Optional<ChannelServer> channelServer) {
        this.serverName = serverName;
        this.profile = profile;
        this.role = role;
        this.loginServer = loginServer;
        this.channelServer = channelServer;
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        requireGameNetworkReady();
        log.info(I18n.message("log.bootstrap.startup_complete"),
                serverName,
                profile,
                TwinkleApplication.startupElapsedSeconds());
    }

    private void requireGameNetworkReady() {
        boolean managementExpected = role.isBlank() || "coordinator".equalsIgnoreCase(role);
        boolean channelExpected = role.isBlank() || "channel".equalsIgnoreCase(role);
        if (managementExpected && loginServer.filter(LoginServer::isRunning).isEmpty()) {
            throw new IllegalStateException(I18n.message("error.bootstrap.component_not_ready", "login Netty"));
        }
        if (channelExpected && channelServer.filter(ChannelServer::isRunning).isEmpty()) {
            throw new IllegalStateException(I18n.message("error.bootstrap.component_not_ready", "channel Netty"));
        }
    }
}
