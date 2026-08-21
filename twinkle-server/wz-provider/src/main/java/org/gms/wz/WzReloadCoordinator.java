package org.gms.wz;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 统一编排 WZ 资源快照与在线运行态投影的热重载。 */
public final class WzReloadCoordinator {

    public record ReloadReport(long version, Map<String, Integer> resources,
                               Map<String, Integer> runtimeObjects) {
        public ReloadReport {
            resources = Map.copyOf(resources);
            runtimeObjects = Map.copyOf(runtimeObjects);
        }
    }

    private record Pending(String name, WzReloadParticipant.PreparedChange change) {
    }

    private final WzResourceRegistry registry;
    private final List<WzReloadParticipant> participants;

    public WzReloadCoordinator(WzResourceRegistry registry, List<WzReloadParticipant> participants) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.participants = validate(participants);
    }

    /**
     * 先在当前快照之外构建、解析并校验所有变更，再发布资源快照与运行态投影。
     * 任一 prepare 失败时，当前资源和在线对象均保持原样。
     */
    public synchronized ReloadReport reload() {
        WzResourceRegistry.PreparedReload preparedResources = registry.prepareReload();
        List<Pending> pending = new ArrayList<>(participants.size());
        for (WzReloadParticipant participant : participants) {
            pending.add(new Pending(participant.name(),
                    Objects.requireNonNull(participant.prepare(preparedResources),
                            () -> "WZ reload participant returned null: " + participant.name())));
        }

        WzResourceRegistry.ReloadReport resources = registry.commit(preparedResources);
        Map<String, Integer> runtimeObjects = new LinkedHashMap<>();
        for (Pending item : pending) {
            runtimeObjects.put(item.name(), item.change().publish());
        }
        return new ReloadReport(resources.version(), resources.resources(), runtimeObjects);
    }

    private static List<WzReloadParticipant> validate(List<WzReloadParticipant> candidates) {
        List<WzReloadParticipant> result = new ArrayList<>(
                Objects.requireNonNull(candidates, "participants"));
        result.sort(Comparator.comparing(WzReloadParticipant::name));
        Map<String, WzReloadParticipant> unique = new LinkedHashMap<>();
        for (WzReloadParticipant participant : result) {
            WzReloadParticipant previous = unique.putIfAbsent(participant.name(), participant);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate WZ reload participant: " + participant.name());
            }
        }
        return List.copyOf(result);
    }
}
