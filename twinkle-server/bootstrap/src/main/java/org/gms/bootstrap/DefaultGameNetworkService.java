package org.gms.bootstrap;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import lombok.extern.log4j.Log4j2;
import org.gms.channel.ChannelServer;
import org.gms.channel.persist.RestartService;
import org.gms.i18n.I18n;
import org.gms.net.netty.LoginServer;
import org.gms.role.ManagementProcessCondition;
import org.gms.service.network.GameNetworkService;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** 管理 HTTP 与游戏 Netty 之间的生命周期边界；重启任务在虚拟线程执行，不阻塞 HTTP。 */
@Singleton
@Requires(condition = ManagementProcessCondition.class)
@Log4j2
public final class DefaultGameNetworkService implements GameNetworkService {

    private final Optional<LoginServer> loginServer;
    private final Optional<ChannelServer> channelServer;
    private final Optional<RestartService> restartService;
    private final int loginPort;
    private final int channelId;
    private final int channelPort;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.RUNNING);
    private volatile String lastError;

    public DefaultGameNetworkService(
            Optional<LoginServer> loginServer,
            Optional<ChannelServer> channelServer,
            Optional<RestartService> restartService,
            @Property(name = "twinkle.net.login.port", defaultValue = "8484") int loginPort,
            @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId,
            @Property(name = "twinkle.net.channel.port", defaultValue = "8584") int channelPort) {
        this.loginServer = loginServer;
        this.channelServer = channelServer;
        this.restartService = restartService;
        this.loginPort = loginPort;
        this.channelId = channelId;
        this.channelPort = channelPort;
    }

    @Override
    public boolean requestRestart() {
        if (!phase.compareAndSet(Phase.RUNNING, Phase.RESTARTING)
                && !phase.compareAndSet(Phase.FAILED, Phase.RESTARTING)) {
            return false;
        }
        lastError = null;
        Thread.ofVirtual().name("game-network-restart").start(this::restart);
        return true;
    }

    @Override
    public Status status() {
        return new Status(
                phase.get(),
                loginServer.map(LoginServer::isRunning).orElse(false),
                loginPort,
                channelServer.map(ChannelServer::isRunning).orElse(false),
                channelId,
                channelPort,
                lastError);
    }

    private void restart() {
        log.info(I18n.message("log.network.restart_requested"));
        try {
            loginServer.ifPresent(LoginServer::stop);
            if (channelServer.isPresent()) {
                RestartService coordinator = restartService.orElseThrow(() ->
                        new IllegalStateException(I18n.message("error.restart.coordinator_missing")));
                ChannelServer server = channelServer.orElseThrow();
                coordinator.restartNetwork(server::stop, () -> server.start(channelPort));
            }
            loginServer.ifPresent(server -> server.start(loginPort));
            phase.set(Phase.RUNNING);
            log.info(I18n.message("log.network.restart_complete"), loginPort, channelId, channelPort);
        } catch (RuntimeException e) {
            restoreListeningSockets();
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            phase.set(Phase.FAILED);
            log.error(I18n.message("log.network.restart_failed"), e);
        }
    }

    private void restoreListeningSockets() {
        channelServer.filter(server -> !server.isRunning()).ifPresent(server -> {
            try {
                server.start(channelPort);
            } catch (RuntimeException restoreError) {
                log.error(I18n.message("log.network.restore_failed"), "channel", restoreError);
            }
        });
        loginServer.filter(server -> !server.isRunning()).ifPresent(server -> {
            try {
                server.start(loginPort);
            } catch (RuntimeException restoreError) {
                log.error(I18n.message("log.network.restore_failed"), "login", restoreError);
            }
        });
    }
}
