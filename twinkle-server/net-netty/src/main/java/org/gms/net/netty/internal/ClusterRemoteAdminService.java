package org.gms.net.netty.internal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.gms.diagnostics.PacketTrace;
import org.gms.hotreload.RestartCoordinator;
import org.gms.module.ModuleRuntime;
import org.gms.service.admin.AdminService;
import org.gms.service.admin.LogicReloadReport;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult;
import org.gms.service.intercoord.ChannelDirectoryService;
import org.gms.service.intercoord.IntercoordService;



/** 管理进程的集群 AdminService：频道操作按频道路由，资源重载按 worker 去重并发执行。 */
public final class ClusterRemoteAdminService implements AdminService {

    @Override public LogicReloadReport reloadLogic(String module) {
        List<List<ModuleRuntime.Update>> reports = parallel(workerRepresentatives(), channel -> {
            try {
                return remote(channel).reloadLogic(module).updates().stream().map(update -> {
                    Map<String,String> targets = new LinkedHashMap<>();
                    update.targets().forEach((name,status) -> targets.put("channel:" + channel + "/" + name, status));
                    return new ModuleRuntime.Update(update.module(), update.digest(), targets);
                }).toList();
            } catch (RuntimeException failure) {
                return List.of(new ModuleRuntime.Update(module, "", Map.of("channel:" + channel, "UNKNOWN")));
            }
        });
        return new LogicReloadReport(reports.stream().flatMap(List::stream).toList());
    }

    @Override public RewardResult grantReward(RewardGrant grant) {
        return remote(channelFor(grant.characterId())).grantReward(grant);
    }

    private final IntFunction<AdminService> adminFactory;
    private final IntercoordService intercoord;
    private final int fallbackChannelId;

    public ClusterRemoteAdminService(CoordinatorLink link, IntercoordService intercoord, int fallbackChannelId) {
        this(channel -> new RemoteAdminService(link, channel), intercoord, fallbackChannelId);
    }
    public ClusterRemoteAdminService(IntFunction<AdminService> adminFactory, IntercoordService intercoord, int fallbackChannelId) {
        this.adminFactory = adminFactory;
        this.intercoord = intercoord;
        this.fallbackChannelId = fallbackChannelId;
    }

    @Override
    public ChannelSummary onlineSummary() {
        List<OnlinePlayer> players = representativesByChannel().stream()
                .flatMap(channelId -> remote(channelId).onlineSummary().players().stream())
                .sorted(Comparator.comparingLong(OnlinePlayer::characterId)).toList();
        return new ChannelSummary(players.size(), 0, players);
    }

    @Override
    public CharacterInventory inventorySnapshot(long characterId) {
        return remote(channelFor(characterId)).inventorySnapshot(characterId);
    }

    @Override
    public boolean kick(long characterId) {
        return remote(channelFor(characterId)).kick(characterId);
    }

    @Override public PacketTrace.Catalog packetTraceCatalog() { return remote(fallback()).packetTraceCatalog(); }
    @Override public PacketTrace.Snapshot startPacketTrace(long id, PacketTrace.Config config) {
        return remote(channelFor(id)).startPacketTrace(id, config);
    }
    @Override public PacketTrace.Snapshot packetTraceSnapshot(long id, long after, int limit) {
        return remote(channelFor(id)).packetTraceSnapshot(id, after, limit);
    }
    @Override public PacketTrace.Snapshot stopPacketTrace(long id) {
        return remote(channelFor(id)).stopPacketTrace(id);
    }

    @Override
    public int reloadScripts() {
        return parallel(workerRepresentatives(), channelId -> remote(channelId).reloadScripts()).stream()
                .mapToInt(Integer::intValue).sum();
    }

    private String pendingWzDigest;
    private List<Integer> pendingWzWorkers;
    private final Map<Integer, WzReloadResult> completedWzWorkers = new LinkedHashMap<>();

    @Override
    public synchronized WzReloadResult reloadWz() {
        List<Integer> workers = pendingWzWorkers == null ? workerRepresentatives() : pendingWzWorkers;
        if (pendingWzDigest == null) {
            List<String> digests = parallel(workers, channelId -> remote(channelId).prepareWz());
            if (digests.isEmpty() || digests.stream().anyMatch(d -> d == null || d.isBlank())
                    || digests.stream().distinct().count() != 1) {
                RuntimeException failure = new IllegalStateException("Workers have different WZ content; release rejected before commit");
                for (int worker : workers) {
                    try { remote(worker).discardPreparedWz(); }
                    catch (RuntimeException error) { failure.addSuppressed(error); }
                }
                throw failure;
            }
            pendingWzDigest = digests.getFirst();
            pendingWzWorkers = List.copyOf(workers);
        }
        String digest = pendingWzDigest;
        Map<String, Integer> resources = new LinkedHashMap<>(), objects = new LinkedHashMap<>();
        Map<String, Long> versions = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        long version = 0;
        for (int worker : workers) {
            try {
                WzReloadResult report = completedWzWorkers.get(worker);
                if (report == null) report = remote(worker).commitWz(digest);
                if (!digest.equals(report.digest())) throw new IllegalStateException("WZ response digest mismatch");
                if (report.failures().isEmpty()) completedWzWorkers.put(worker, report);
                version = Math.max(version, report.version());
                report.resources().forEach(resources::putIfAbsent);
                report.runtimeObjects().forEach((key, value) -> objects.put(worker + "/" + key, value));
                report.versions().forEach((key, value) -> versions.put(worker + "/" + key, value));
                report.failures().forEach((key, value) -> failures.put(worker + "/" + key, value));
            } catch (RuntimeException error) { failures.put("worker:" + worker, "UNKNOWN: " + error); }
        }
        if (failures.isEmpty()) { pendingWzDigest = null; pendingWzWorkers = null; completedWzWorkers.clear(); }
        return new WzReloadResult(version, resources, objects, digest, versions, failures);
    }

    @Override
    public void requestRestart() {
        workerRepresentatives().forEach(channelId -> remote(channelId).requestRestart());
    }

    @Override
    public RestartCoordinator.Phase restartPhase() {
        return remote(fallback()).restartPhase();
    }

    private int channelFor(long characterId) {
        return intercoord.locate(characterId).orElse(fallback());
    }

    private int fallback() {
        return intercoord.channel(fallbackChannelId).map(ChannelDirectoryService.ChannelInfo::channelId)
                .orElseGet(() -> intercoord.channels().keySet().stream().min(Integer::compareTo)
                        .orElse(fallbackChannelId));
    }

    private AdminService remote(int channelId) { return adminFactory.apply(channelId); }

    private List<Integer> representativesByChannel() {
        return intercoord.channels().keySet().stream().sorted().toList();
    }

    private List<Integer> workerRepresentatives() {
        Map<String, Integer> representatives = new LinkedHashMap<>();
        intercoord.channels().values().stream()
                .sorted(Comparator.comparingInt(ChannelDirectoryService.ChannelInfo::channelId))
                .forEach(info -> {
                    String key = info.workerId() == null || info.workerId().isBlank()
                            ? "channel:" + info.channelId() : info.workerId();
                    representatives.putIfAbsent(key, info.channelId());
                });
        if (representatives.isEmpty()) return List.of(fallback());
        return List.copyOf(representatives.values());
    }

    private static <T> List<T> parallel(List<Integer> channels,
                                        java.util.function.Function<Integer, T> operation) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = channels.stream().map(id -> executor.submit(() -> operation.apply(id))).toList();
            List<T> result = new ArrayList<>(futures.size());
            for (Future<T> future : futures) {
                try {
                    result.add(future.get());
                } catch (Exception e) {
                    throw new IllegalStateException("Cluster admin operation failed", e);
                }
            }
            return List.copyOf(result);
        }
    }
}
