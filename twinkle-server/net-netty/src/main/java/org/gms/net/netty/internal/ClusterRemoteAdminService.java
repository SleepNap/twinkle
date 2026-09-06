package org.gms.net.netty.internal;
import org.gms.service.intercoord.ChannelDirectoryService;

import org.gms.diagnostics.PacketTrace;
import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;
import org.gms.service.intercoord.IntercoordService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 管理进程的集群 AdminService：频道操作按频道路由，资源重载按 worker 去重并发执行。 */
public final class ClusterRemoteAdminService implements AdminService {

    private final CoordinatorLink link;
    private final IntercoordService intercoord;
    private final int fallbackChannelId;

    public ClusterRemoteAdminService(CoordinatorLink link, IntercoordService intercoord, int fallbackChannelId) {
        this.link = link;
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

    @Override
    public WzReloadResult reloadWz() {
        List<WzReloadResult> reports = parallel(workerRepresentatives(), channelId -> remote(channelId).reloadWz());
        long version = reports.stream().mapToLong(WzReloadResult::version).max().orElse(0);
        Map<String, Integer> resources = new LinkedHashMap<>();
        Map<String, Integer> runtimeObjects = new LinkedHashMap<>();
        for (WzReloadResult report : reports) {
            report.resources().forEach(resources::putIfAbsent);
            report.runtimeObjects().forEach((name, count) -> runtimeObjects.merge(name, count, Integer::sum));
        }
        return new WzReloadResult(version, resources, runtimeObjects);
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

    private RemoteAdminService remote(int channelId) { return new RemoteAdminService(link, channelId); }

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
