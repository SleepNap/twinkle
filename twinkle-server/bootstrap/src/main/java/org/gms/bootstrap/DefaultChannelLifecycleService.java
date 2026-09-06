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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Worker 本地频道监听生命周期；每个频道独立启停，最后一个 terminate 才退出 Worker JVM。 */
@Singleton
@Requires(condition = ChannelProcessCondition.class)
public final class DefaultChannelLifecycleService implements ChannelLifecycleService {

    private static final class Local {
        final int channelId;
        final String host;
        final int port;
        final ChannelServer server;
        final PlayerStorage players;
        final AtomicReference<State> state;
        volatile String lastError;

        Local(int channelId, String host, int port, ChannelServer server, PlayerStorage players) {
            this.channelId = channelId;
            this.host = host;
            this.port = port;
            this.server = server;
            this.players = players;
            this.state = new AtomicReference<>(server.isRunning() ? State.RUNNING : State.STOPPED);
        }
    }

    private final Map<Integer, Local> channels;
    private final RestartService restartService;
    private final ChannelWorker worker;
    private final Topology topology;
    private final Runnable exitProcess;
    private final Set<Integer> terminateRequested = ConcurrentHashMap.newKeySet();

    @Inject
    public DefaultChannelLifecycleService(
            ChannelWorker worker,
            RestartService restartService,
            @Property(name = "twinkle.role", defaultValue = "") String role,
            @Property(name = "twinkle.admin.shutdown.exit", defaultValue = "true") boolean exitOnShutdown) {
        Map<Integer, Local> locals = new LinkedHashMap<>();
        worker.runtimes().forEach(runtime -> locals.put(runtime.channelId(), new Local(
                runtime.channelId(), runtime.host(), runtime.port(), runtime.server(), runtime.players())));
        this.channels = Map.copyOf(locals);
        this.restartService = restartService;
        this.worker = worker;
        this.topology = role.isBlank() ? Topology.EMBEDDED : Topology.DISTRIBUTED;
        this.exitProcess = exitOnShutdown ? () -> System.exit(0) : () -> { };
    }

    /** 单频道兼容构造，保留既有生命周期单元测试。 */
    DefaultChannelLifecycleService(ChannelServer channelServer, PlayerStorage playerStorage,
                                   RestartService restartService, int channelId, String host, int port,
                                   String role, Runnable exitProcess) {
        this.channels = Map.of(channelId, new Local(channelId, host, port, channelServer, playerStorage));
        this.restartService = restartService;
        this.worker = null;
        this.topology = role.isBlank() ? Topology.EMBEDDED : Topology.DISTRIBUTED;
        this.exitProcess = exitProcess;
    }

    @Override
    public List<Status> statuses() {
        return channels.values().stream().map(this::status).toList();
    }

    @Override
    public CommandResult requestStart(int channelId) {
        Local local = channels.get(channelId);
        if (local == null) return unknown(channelId);
        State current = local.state.get();
        if (local.server.isRunning()) {
            local.state.set(State.RUNNING);
            return new CommandResult(false, status(local));
        }
        if ((current != State.STOPPED && current != State.FAILED)
                || !local.state.compareAndSet(current, State.STARTING)) {
            return new CommandResult(false, status(local));
        }
        local.lastError = null;
        terminateRequested.remove(channelId);
        Thread.ofVirtual().name("channel-" + channelId + "-start").start(() -> start(local));
        return new CommandResult(true, status(local));
    }

    @Override public CommandResult requestStop(int channelId) { return requestStop(channelId, false); }

    @Override
    public CommandResult requestStop(int channelId, boolean force) {
        Local local = channels.get(channelId);
        if (local == null) return unknown(channelId);
        State current = local.state.get();
        if (!local.server.isRunning() && current != State.FAILED) {
            local.state.set(State.STOPPED);
            return new CommandResult(false, status(local));
        }
        if ((current != State.RUNNING && current != State.FAILED)
                || !local.state.compareAndSet(current, State.STOPPING)) {
            return new CommandResult(false, status(local));
        }
        local.lastError = null;
        Thread.ofVirtual().name("channel-" + channelId + "-stop").start(() -> stop(local, force));
        return new CommandResult(true, status(local));
    }

    @Override public CommandResult requestTerminate(int channelId) { return requestTerminate(channelId, false); }

    @Override
    public CommandResult requestTerminate(int channelId, boolean force) {
        Local local = channels.get(channelId);
        if (local == null) return unknown(channelId);
        State current = local.state.get();
        if (current == State.STARTING || current == State.STOPPING || current == State.TERMINATING
                || !local.state.compareAndSet(current, State.TERMINATING)) {
            return new CommandResult(false, status(local));
        }
        terminateRequested.add(channelId);
        local.lastError = null;
        Thread.ofVirtual().name("channel-" + channelId + "-terminate").start(() -> terminate(local, force));
        return new CommandResult(true, status(local));
    }

    private void start(Local local) {
        try {
            restartService.ensureSavesPersisted();
            startNetwork(local);
            local.state.set(State.RUNNING);
        } catch (RuntimeException e) {
            fail(local, e);
        }
    }

    private void stop(Local local, boolean force) {
        try {
            restartService.stopNetwork(() -> stopNetwork(local), force);
            local.state.set(State.STOPPED);
        } catch (RuntimeException e) {
            fail(local, e);
        }
    }

    private void terminate(Local local, boolean force) {
        try {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
            if (force) {
                // force 是显式的最终兜底：立即摘除路由并关闭监听，不再被已知失败的存档闸门卡死。
                stopNetwork(local);
                local.state.set(State.STOPPED);
                exitIfAllTerminated();
                return;
            }
            if (local.server.isRunning()) {
                restartService.stopNetwork(() -> stopNetwork(local), false);
            } else {
                restartService.ensureSavesPersisted();
            }
            local.state.set(State.STOPPED);
            exitIfAllTerminated();
        } catch (RuntimeException e) {
            fail(local, e);
        }
    }

    private void startNetwork(Local local) {
        if (worker == null) local.server.start(local.port);
        else worker.start(local.channelId);
    }

    private void stopNetwork(Local local) {
        if (worker == null) local.server.stop();
        else worker.stop(local.channelId);
    }

    private void exitIfAllTerminated() {
        if (terminateRequested.containsAll(channels.keySet())) exitProcess.run();
    }

    private void fail(Local local, RuntimeException error) {
        local.lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        local.state.set(State.FAILED);
    }

    private Status status(Local local) {
        State current = local.state.get();
        boolean running = local.server.isRunning();
        if (current != State.STARTING && current != State.STOPPING && current != State.TERMINATING) {
            if (running && current != State.RUNNING) {
                local.state.compareAndSet(current, State.RUNNING);
                current = local.state.get();
                if (current == State.RUNNING) local.lastError = null;
            } else if (!running && current == State.RUNNING) {
                local.state.compareAndSet(State.RUNNING, State.STOPPED);
                current = local.state.get();
            }
        }
        return new Status(local.channelId, local.host, local.port, local.players.count(), current,
                topology, true, local.lastError);
    }

    private CommandResult unknown(int channelId) {
        return new CommandResult(false, new Status(channelId, "", 0, 0, State.UNAVAILABLE,
                topology, false, I18n.message("error.channel.lifecycle.unknown", channelId)));
    }
}
