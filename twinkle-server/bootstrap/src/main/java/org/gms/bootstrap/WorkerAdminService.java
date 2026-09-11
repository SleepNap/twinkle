package org.gms.bootstrap;

import org.gms.diagnostics.PacketTrace;
import org.gms.hotreload.RestartCoordinator;
import org.gms.service.admin.AdminService;
import org.gms.service.admin.LogicReloadReport;
import org.gms.module.ModuleRegistry;
import org.gms.service.admin.RewardGrant;
import org.gms.service.admin.RewardResult;

import java.util.Comparator;
import java.util.List;

/** Worker 内 AdminService 聚合门面；资源重载执行一次，玩家操作按频道运行时定位。 */
public final class WorkerAdminService implements AdminService {

    @Override
    public RewardResult grantReward(RewardGrant grant) {
        for (ChannelRuntime runtime : worker.runtimes()) {
            // 只读目录定位后再投递，不先等待其他频道的游戏队列。
            if (runtime.players().getById(grant.characterId()) == null) continue;
            RewardResult result = runtime.admin().grantReward(grant);
            if (result.status() != RewardResult.Status.OFFLINE) return result;
        }
        return RewardResult.of(grant, RewardResult.Status.OFFLINE);
    }

    private final ChannelWorker worker;
    private final ModuleRegistry modules;

    public WorkerAdminService(ChannelWorker worker, ModuleRegistry modules) {
        this.modules = modules;
        this.worker = worker;
    }

    @Override public LogicReloadReport reloadLogic(String module) {
        try { return new LogicReloadReport(List.of(modules.reload(module))); }
        catch (Exception failure) { throw new IllegalStateException("逻辑重载未完成：" + module, failure); }
    }

    @Override
    public ChannelSummary onlineSummary() {
        List<OnlinePlayer> players = worker.runtimes().stream()
                .flatMap(runtime -> runtime.admin().onlineSummary().players().stream())
                .sorted(Comparator.comparingLong(OnlinePlayer::characterId))
                .toList();
        return new ChannelSummary(players.size(), 0, players);
    }

    @Override
    public CharacterInventory inventorySnapshot(long characterId) {
        for (ChannelRuntime runtime : worker.runtimes()) {
            CharacterInventory result = runtime.admin().inventorySnapshot(characterId);
            if (result != null) return result;
        }
        return null;
    }

    @Override
    public boolean kick(long characterId) {
        return worker.runtimes().stream().anyMatch(runtime -> runtime.admin().kick(characterId));
    }

    @Override
    public PacketTrace.Catalog packetTraceCatalog() { return first().packetTraceCatalog(); }

    @Override
    public PacketTrace.Snapshot startPacketTrace(long characterId, PacketTrace.Config config) {
        for (ChannelRuntime runtime : worker.runtimes()) {
            PacketTrace.Snapshot result = runtime.admin().startPacketTrace(characterId, config);
            if (result != null) return result;
        }
        return null;
    }

    @Override
    public PacketTrace.Snapshot packetTraceSnapshot(long characterId, long afterSequence, int limit) {
        for (ChannelRuntime runtime : worker.runtimes()) {
            PacketTrace.Snapshot result = runtime.admin().packetTraceSnapshot(characterId, afterSequence, limit);
            if (result != null) return result;
        }
        return null;
    }

    @Override
    public PacketTrace.Snapshot stopPacketTrace(long characterId) {
        for (ChannelRuntime runtime : worker.runtimes()) {
            PacketTrace.Snapshot result = runtime.admin().stopPacketTrace(characterId);
            if (result != null) return result;
        }
        return null;
    }

    @Override public int reloadScripts() { return first().reloadScripts(); }
    @Override public WzReloadResult reloadWz() { return first().reloadWz(); }
    @Override public void requestRestart() { first().requestRestart(); }
    @Override public RestartCoordinator.Phase restartPhase() { return first().restartPhase(); }

    private AdminService first() {
        return worker.runtimes().stream().findFirst().orElseThrow().admin();
    }
}
