package org.gms.bootstrap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.log4j.Log4j2;
import org.gms.channel.ChannelPlayerDirectory;
import org.gms.channel.admin.ChannelAdminService;
import org.gms.channel.persist.RestartService;
import org.gms.domain.script.ScriptManager;
import org.gms.hotreload.RestartCoordinator;
import org.gms.i18n.I18n;
import org.gms.net.packet.HandlerRegistry;
import org.gms.service.intercoord.IntercoordService;
import org.gms.tick.TickScheduler;
import org.gms.wz.WzReloadCoordinator;
import org.gms.wz.WzReloadParticipant;
import org.gms.wz.WzResourceRegistry;



/**
 * 进程级频道编排器。一个 Worker 共享一份 WZ、脚本、主 Tick 和存档队列，管理多个隔离的
 * {@link ChannelRuntime}；频道 ID 只是路由键，不要求连续，也不决定进程数量。
 */
@Log4j2
public final class ChannelWorker implements AutoCloseable {

    private final ChannelRuntimeFactory runtimeFactory;
    private final ChannelWorkerSpec spec;
    private final IntercoordService intercoord;
    private final TickScheduler tickScheduler;
    private final ChannelPlayerDirectory playerDirectory;
    private final Map<Integer, ChannelRuntime> runtimes;
    private final WzReloadCoordinator wzReloadCoordinator;

    public ChannelWorker(ChannelWorkerSpec spec, ChannelRuntimeFactory runtimeFactory,
                         WzResourceRegistry wzResources, ScriptManager scriptManager,
                         RestartService restartService, RestartCoordinator restartCoordinator,
                         IntercoordService intercoord, ChannelPlayerDirectory playerDirectory,
                         TickScheduler tickScheduler, boolean exitOnRestart) {
        this.spec = spec;
        this.runtimeFactory = runtimeFactory;
        this.intercoord = intercoord;
        this.playerDirectory = playerDirectory;
        this.tickScheduler = tickScheduler;

        Map<Integer, ChannelRuntime> built = new LinkedHashMap<>();
        try {
            for (ChannelWorkerSpec.Endpoint endpoint : spec.endpoints()) {
                ChannelRuntime runtime = runtimeFactory.create(endpoint);
                built.put(endpoint.channelId(), runtime);
            }
        } catch (RuntimeException error) {
            built.values().forEach(ChannelRuntime::close);
            built.values().forEach(runtime ->
                    playerDirectory.unregister(runtime.channelId(), runtime.players()));
            throw error;
        }
        this.runtimes = Map.copyOf(built);
        this.wzReloadCoordinator = new WzReloadCoordinator(wzResources,
                runtimes.values().stream().map(runtime -> (WzReloadParticipant) runtime.maps()).toList());

        Runnable restartProcess = exitOnRestart ? () -> System.exit(0) : () -> { };
        runtimes.values().forEach(runtime -> runtime.admin(new ChannelAdminService(
                runtime.players(), runtime.sessions(), runtime.channelId(), scriptManager,
                wzReloadCoordinator, restartService, restartCoordinator, this::stopAll, restartProcess)));
    }

    public String workerId() { return spec.workerId(); }
    public Collection<ChannelRuntime> runtimes() { return runtimes.values(); }
    public ChannelRuntime runtime(int channelId) { return runtimes.get(channelId); }
    public List<Integer> channelIds() { return runtimes.keySet().stream().sorted().toList(); }
    public List<HandlerRegistry> handlerRegistries() {
        return runtimes.values().stream().map(ChannelRuntime::handlers).toList();
    }
    public WzReloadCoordinator wzReloadCoordinator() { return wzReloadCoordinator; }
    public boolean anyRunning() {
        return runtimes.values().stream().anyMatch(runtime -> runtime.server().isRunning());
    }

    public synchronized void start(int channelId) {
        ChannelRuntime runtime = requireRuntime(channelId);
        if (!runtime.server().isRunning()) runtime.server().start(runtime.port());
        intercoord.registerChannel(runtime.channelId(), runtime.host(), runtime.port(),
                runtime.players().count(), workerId());
        log.info(I18n.message("log.bootstrap.channel_started"), channelId, runtime.server().boundPort());
    }

    public synchronized void stop(int channelId) {
        ChannelRuntime runtime = requireRuntime(channelId);
        // 先从拓扑摘除，禁止登录/换线继续路由到正在关闭的监听器。
        intercoord.unregisterChannel(channelId);
        runtime.server().stop();
        runtime.drainStateTasks();
        runtimeFactory.drainIo();
    }

    public synchronized void startAll() {
        List<ChannelRuntime> started = new ArrayList<>();
        try {
            for (ChannelRuntime runtime : runtimes.values()) {
                start(runtime.channelId());
                started.add(runtime);
            }
            tickScheduler.start();
        } catch (RuntimeException error) {
            started.reversed().forEach(runtime -> stop(runtime.channelId()));
            throw error;
        }
    }

    /** 只停止玩家监听，保留频道运行态、共享资源和 worker 控制链路。 */
    public synchronized void stopAll() {
        runtimes.values().forEach(runtime -> stop(runtime.channelId()));
    }

    @Override
    public synchronized void close() {
        stopAll();
        runtimeFactory.drainSaves();
        for (ChannelRuntime runtime : runtimes.values()) {
            intercoord.unregisterChannel(runtime.channelId());
            runtime.close();
            playerDirectory.unregister(runtime.channelId(), runtime.players());
        }
        runtimeFactory.close();
    }

    private ChannelRuntime requireRuntime(int channelId) {
        ChannelRuntime runtime = runtimes.get(channelId);
        if (runtime == null) throw new IllegalArgumentException("Unknown channel " + channelId);
        return runtime;
    }
}
