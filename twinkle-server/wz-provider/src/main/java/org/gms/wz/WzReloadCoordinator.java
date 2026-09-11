package org.gms.wz;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/** WZ 逐频道发布；部分失败固定候选并重试，禁止在未收敛时继续产生第三代。 */
public final class WzReloadCoordinator {
    public record ReloadReport(long version, Map<String, Integer> resources,
                               Map<String, Integer> runtimeObjects, String digest,
                               Map<String, Long> versions, Map<String, String> failures) {
        public ReloadReport {
            resources = Map.copyOf(resources); runtimeObjects = Map.copyOf(runtimeObjects);
            versions = Map.copyOf(versions); failures = Map.copyOf(failures);
        }
    }
    private final WzResourceRegistry registry;
    private final List<WzReloadParticipant> participants;
    private WzResourceRegistry.PreparedReload candidate;
    private List<Pending> pending;
    private boolean committed;
    private ReloadReport lastCompleted;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, Long> versions = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private record Pending(String name, WzReloadParticipant.PreparedChange change) { }

    public WzReloadCoordinator(WzResourceRegistry registry, List<WzReloadParticipant> participants) {
        this.registry = Objects.requireNonNull(registry);
        this.participants = participants.stream().sorted(Comparator.comparing(WzReloadParticipant::name)).toList();
        if (this.participants.stream().map(WzReloadParticipant::name).distinct().count() != participants.size())
            throw new IllegalArgumentException("Duplicate WZ reload participant");
        this.participants.forEach(p -> versions.put(p.name(), registry.version()));
    }
    /** 集群先取得各 Worker 的候选摘要，完全相同后才允许逐 Worker 发布。 */
    public synchronized String prepare() {
        if (candidate != null) return candidate.digest();
        candidate = null; pending = null;
        WzResourceRegistry.PreparedReload next = registry.prepareReload();
        List<Pending> changes = new ArrayList<>();
        for (WzReloadParticipant participant : participants)
            changes.add(new Pending(participant.name(), Objects.requireNonNull(participant.prepare(next))));
        candidate = next; pending = changes; counts.clear(); failures.clear();
        return next.digest();
    }
    /** 集群摘要不一致时撤销尚未发布的准备，修正源文件后可重新预检。 */
    public synchronized void discardPrepared() {
        if (committed) throw new IllegalStateException("Cannot discard a partially published WZ release");
        candidate = null; pending = null;
    }
    public synchronized ReloadReport reload() {
        if (candidate == null) prepare();
        return reload(candidate.digest());
    }
    public synchronized ReloadReport reload(String expectedDigest) {
        if (candidate == null && lastCompleted != null && lastCompleted.digest().equals(expectedDigest)) return lastCompleted;
        if (candidate == null || !candidate.digest().equals(expectedDigest))
            throw new IllegalStateException("WZ candidate differs from prepared release");
        if (!committed) { registry.commit(candidate); committed = true; }
        failures.clear();
        for (Pending item : pending) {
            if (counts.containsKey(item.name())) continue;
            try {
                counts.put(item.name(), item.change().publish());
                versions.put(item.name(), candidate.version());
            } catch (RuntimeException error) { failures.put(item.name(), error.toString()); }
        }
        ReloadReport result = new ReloadReport(candidate.version(), registry.status().resources(), counts,
                candidate.digest(), versions, failures);
        if (failures.isEmpty()) { lastCompleted = result; candidate = null; pending = null; committed = false; }
        return result;
    }
}
