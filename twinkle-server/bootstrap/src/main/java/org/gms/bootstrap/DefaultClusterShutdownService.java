package org.gms.bootstrap;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.gms.i18n.I18n;
import org.gms.net.netty.LoginServer;
import org.gms.role.ManagementProcessCondition;
import org.gms.service.channel.ChannelLifecycleService;
import org.gms.service.shutdown.ClusterShutdownService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 管理进程侧两阶段集群关停：先排空全部频道，再退出频道工作进程与 coordinator。 */
@Singleton
@Requires(condition = ManagementProcessCondition.class)
public final class DefaultClusterShutdownService implements ClusterShutdownService {

    private final ChannelLifecycleService channelLifecycleService;
    private final Runnable stopLogin;
    private final Runnable exitProcess;
    private final long timeoutMillis;
    private final AtomicReference<Status> status = new AtomicReference<>(
            new Status(Phase.RUNNING, 0, 0, List.of(), null));

    @Inject
    public DefaultClusterShutdownService(
            ChannelLifecycleService channelLifecycleService,
            LoginServer loginServer,
            @Property(name = "twinkle.admin.shutdown.timeout", defaultValue = "30000") long timeoutMillis,
            @Property(name = "twinkle.admin.shutdown.exit", defaultValue = "true") boolean exitOnShutdown) {
        this(channelLifecycleService, loginServer::stop, timeoutMillis,
                exitOnShutdown ? () -> System.exit(0) : () -> { });
    }

    DefaultClusterShutdownService(ChannelLifecycleService channelLifecycleService, Runnable stopLogin,
                                  long timeoutMillis, Runnable exitProcess) {
        this.channelLifecycleService = channelLifecycleService;
        this.stopLogin = stopLogin;
        this.timeoutMillis = timeoutMillis;
        this.exitProcess = exitProcess;
    }

    @Override
    public CommandResult requestShutdown() {
        return requestShutdown(false);
    }

    @Override
    public CommandResult requestShutdown(boolean force) {
        if (!begin()) {
            return new CommandResult(false, status());
        }
        Thread.ofVirtual().name("cluster-shutdown").start(() -> shutdown(true, force));
        return new CommandResult(true, status());
    }

    @Override
    public Status status() {
        return status.get();
    }

    /** 正常 JVM/Micronaut 关闭事件入口；不再次退出当前进程。 */
    void shutdownForContextClose() {
        if (begin()) {
            shutdown(false, false);
            return;
        }
        awaitExistingShutdown();
    }

    private boolean begin() {
        while (true) {
            Status current = status.get();
            if (current.phase() != Phase.RUNNING && current.phase() != Phase.PARTIAL_FAILURE) {
                return false;
            }
            Status draining = new Status(Phase.DRAINING_CHANNELS, 0, 0, List.of(), null);
            if (status.compareAndSet(current, draining)) {
                return true;
            }
        }
    }

    private void shutdown(boolean exitCoordinator, boolean force) {
        List<Integer> targets = List.of();
        try {
            stopLogin.run();
            List<ChannelLifecycleService.Status> initial = channelLifecycleService.statuses();
            targets = initial.stream().map(ChannelLifecycleService.Status::channelId)
                    .distinct().sorted().toList();
            status.set(new Status(Phase.DRAINING_CHANNELS, targets.size(), 0, List.of(), null));

            List<Integer> failed = drainChannels(targets);
            if (!failed.isEmpty() && !force) {
                fail(targets.size(), targets.size() - failed.size(), failed,
                        I18n.message("error.cluster.shutdown.timeout"));
                return;
            }
            List<Integer> unsafe = new ArrayList<>(failed);

            status.set(new Status(Phase.TERMINATING_CHANNELS, targets.size(), targets.size(),
                    unsafe.stream().distinct().sorted().toList(),
                    unsafe.isEmpty() ? null : I18n.message("error.cluster.shutdown.forced")));
            failed = terminateDistributedChannels(initial, force);
            if (!failed.isEmpty() && !force) {
                fail(targets.size(), targets.size() - failed.size(), failed,
                        I18n.message("error.cluster.shutdown.terminate_failed"));
                return;
            }
            unsafe.addAll(failed);
            List<Integer> unsafeIds = unsafe.stream().distinct().sorted().toList();

            status.set(new Status(Phase.STOPPING_COORDINATOR, targets.size(),
                    targets.size() - unsafeIds.size(), unsafeIds,
                    unsafeIds.isEmpty() ? null : I18n.message("error.cluster.shutdown.forced")));
            if (exitCoordinator) {
                exitProcess.run();
            }
        } catch (RuntimeException e) {
            fail(targets.size(), 0, targets,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private List<Integer> drainChannels(List<Integer> targets) {
        if (targets.isEmpty()) {
            return List.of();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Set<Integer> stopped = new LinkedHashSet<>();
        Set<Integer> stopRequested = new LinkedHashSet<>();
        while (System.nanoTime() < deadline) {
            Map<Integer, ChannelLifecycleService.Status> current = statusesById();
            for (int channelId : targets) {
                ChannelLifecycleService.Status channel = current.get(channelId);
                if (channel == null || channel.state() == ChannelLifecycleService.State.UNAVAILABLE) {
                    continue;
                }
                if (channel.state() == ChannelLifecycleService.State.STOPPED) {
                    stopped.add(channelId);
                    continue;
                }
                if (channel.state() == ChannelLifecycleService.State.RUNNING
                        || channel.state() == ChannelLifecycleService.State.FAILED) {
                    if (!stopRequested.contains(channelId)
                            && channelLifecycleService.requestStop(channelId).accepted()) {
                        stopRequested.add(channelId);
                    }
                }
            }
            status.set(new Status(Phase.DRAINING_CHANNELS, targets.size(), stopped.size(),
                    List.of(), null));
            if (stopped.size() == targets.size()) {
                return List.of();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        List<Integer> failed = new ArrayList<>(targets);
        failed.removeAll(stopped);
        return List.copyOf(failed);
    }

    private List<Integer> terminateDistributedChannels(
            List<ChannelLifecycleService.Status> initial, boolean force) {
        List<Integer> failed = new ArrayList<>();
        for (ChannelLifecycleService.Status channel : initial) {
            if (channel.topology() == ChannelLifecycleService.Topology.EMBEDDED) {
                continue;
            }
            ChannelLifecycleService.CommandResult result =
                    channelLifecycleService.requestTerminate(channel.channelId(), force);
            if (!result.accepted()) {
                failed.add(channel.channelId());
            }
        }
        return List.copyOf(failed);
    }

    private Map<Integer, ChannelLifecycleService.Status> statusesById() {
        Map<Integer, ChannelLifecycleService.Status> result = new HashMap<>();
        channelLifecycleService.statuses().stream()
                .sorted(Comparator.comparingInt(ChannelLifecycleService.Status::channelId))
                .forEach(channel -> result.put(channel.channelId(), channel));
        return result;
    }

    private void awaitExistingShutdown() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            Phase phase = status.get().phase();
            if (phase == Phase.STOPPING_COORDINATOR || phase == Phase.PARTIAL_FAILURE) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void fail(int targetCount, int completedCount, List<Integer> failed, String error) {
        status.set(new Status(Phase.PARTIAL_FAILURE, targetCount, completedCount,
                failed.stream().distinct().sorted().toList(), error));
    }
}
