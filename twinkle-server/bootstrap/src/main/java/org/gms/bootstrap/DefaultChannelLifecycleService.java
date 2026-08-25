package org.gms.bootstrap;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.gms.channel.ChannelServer;
import org.gms.channel.PlayerStorage;
import org.gms.channel.persist.RestartService;
import org.gms.i18n.I18n;
import org.gms.role.ChannelProcessCondition;
import org.gms.service.channel.ChannelLifecycleService;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** 本地频道监听生命周期；single 由 HTTP 直接调用，split-channel 由 coordinator RPC 调用。 */
@Singleton
@Requires(condition = ChannelProcessCondition.class)
public final class DefaultChannelLifecycleService implements ChannelLifecycleService {

    private final ChannelServer channelServer;
    private final PlayerStorage playerStorage;
    private final RestartService restartService;
    private final int channelId;
    private final String host;
    private final int port;
    private final Topology topology;
    private final Runnable exitProcess;
    private final AtomicReference<State> state;
    private volatile String lastError;

    @Inject
    public DefaultChannelLifecycleService(
            ChannelServer channelServer,
            PlayerStorage playerStorage,
            RestartService restartService,
            @Property(name = "twinkle.net.channel.id", defaultValue = "1") int channelId,
            @Property(name = "twinkle.net.channel.host", defaultValue = "127.0.0.1") String host,
            @Property(name = "twinkle.net.channel.port", defaultValue = "8584") int port,
            @Property(name = "twinkle.role", defaultValue = "") String role,
            @Property(name = "twinkle.admin.shutdown.exit", defaultValue = "true") boolean exitOnShutdown) {
        this(channelServer, playerStorage, restartService, channelId, host, port, role,
                exitOnShutdown ? () -> System.exit(0) : () -> { });
    }

    DefaultChannelLifecycleService(
            ChannelServer channelServer,
            PlayerStorage playerStorage,
            RestartService restartService,
            int channelId,
            String host,
            int port,
            String role,
            Runnable exitProcess) {
        this.channelServer = channelServer;
        this.playerStorage = playerStorage;
        this.restartService = restartService;
        this.channelId = channelId;
        this.host = host;
        this.port = port;
        this.topology = role.isBlank() ? Topology.EMBEDDED : Topology.DISTRIBUTED;
        this.exitProcess = exitProcess;
        this.state = new AtomicReference<>(channelServer.isRunning() ? State.RUNNING : State.STOPPED);
    }

    @Override
    public List<Status> statuses() {
        return List.of(status());
    }

    @Override
    public CommandResult requestStart(int requestedChannelId) {
        if (requestedChannelId != channelId) {
            return new CommandResult(false, unavailable(requestedChannelId));
        }
        State current = state.get();
        if (channelServer.isRunning()) {
            state.set(State.RUNNING);
            return new CommandResult(false, status());
        }
        if ((current != State.STOPPED && current != State.FAILED)
                || !state.compareAndSet(current, State.STARTING)) {
            return new CommandResult(false, status());
        }
        lastError = null;
        Thread.ofVirtual().name("channel-" + channelId + "-start").start(this::start);
        return new CommandResult(true, status());
    }

    @Override
    public CommandResult requestStop(int requestedChannelId) {
        return requestStop(requestedChannelId, false);
    }

    @Override
    public CommandResult requestStop(int requestedChannelId, boolean force) {
        if (requestedChannelId != channelId) {
            return new CommandResult(false, unavailable(requestedChannelId));
        }
        State current = state.get();
        if (!channelServer.isRunning() && current != State.FAILED) {
            state.set(State.STOPPED);
            return new CommandResult(false, status());
        }
        if ((current != State.RUNNING && current != State.FAILED)
                || !state.compareAndSet(current, State.STOPPING)) {
            return new CommandResult(false, status());
        }
        lastError = null;
        Thread.ofVirtual().name("channel-" + channelId + "-stop").start(() -> stop(force));
        return new CommandResult(true, status());
    }

    @Override
    public CommandResult requestTerminate(int requestedChannelId) {
        return requestTerminate(requestedChannelId, false);
    }

    @Override
    public CommandResult requestTerminate(int requestedChannelId, boolean force) {
        if (requestedChannelId != channelId) {
            return new CommandResult(false, unavailable(requestedChannelId));
        }
        State current = state.get();
        if (current == State.STARTING || current == State.STOPPING || current == State.TERMINATING
                || !state.compareAndSet(current, State.TERMINATING)) {
            return new CommandResult(false, status());
        }
        lastError = null;
        Thread.ofVirtual().name("channel-" + channelId + "-terminate").start(() -> terminate(force));
        return new CommandResult(true, status());
    }

    private void start() {
        try {
            restartService.ensureSavesPersisted();
            channelServer.start(port);
            state.set(State.RUNNING);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void stop(boolean force) {
        try {
            try {
                restartService.stopNetwork(channelServer::stop, force);
            } catch (RuntimeException e) {
                if (!force) {
                    throw e;
                }
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            state.set(State.STOPPED);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void terminate(boolean force) {
        try {
            // 让 HTTP/RPC 的 accepted 响应先写回；频道已进入 TERMINATING，不再接受其它生命周期操作。
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
            // 即便监听已因上一次失败关闭，也必须重新验证并重试存档后才能退出。
            try {
                restartService.stopNetwork(channelServer::stop, force);
            } catch (RuntimeException e) {
                if (!force) {
                    throw e;
                }
                lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            exitProcess.run();
            state.compareAndSet(State.TERMINATING, State.STOPPED);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private void fail(RuntimeException error) {
        lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        state.set(State.FAILED);
    }

    private Status status() {
        State current = state.get();
        boolean running = channelServer.isRunning();
        if (current != State.STARTING && current != State.STOPPING && current != State.TERMINATING) {
            if (running && current != State.RUNNING) {
                state.compareAndSet(current, State.RUNNING);
                current = state.get();
                if (current == State.RUNNING) {
                    lastError = null;
                }
            } else if (!running && current == State.RUNNING) {
                state.compareAndSet(State.RUNNING, State.STOPPED);
                current = state.get();
            }
        }
        return new Status(channelId, host, port, playerStorage.all().size(),
                current, topology, true, lastError);
    }

    private Status unavailable(int requestedChannelId) {
        return new Status(requestedChannelId, "", 0, 0, State.UNAVAILABLE, topology, false,
                I18n.message("error.channel.lifecycle.unknown", requestedChannelId));
    }
}
