package org.gms.wz;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.gms.wz.resource.NameResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;


public class WzConsistencyTest {
    @Test public void lazyOldCatalogNeverReadsOverwrittenSource(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("String.wz")).resolve("Item.img.xml");
        Files.writeString(file, xml("old"));
        var registry = new WzResourceRegistry(root, List.of(new NameResourceLoader()), Runnable::run);
        var old = registry.resource(WzResources.NAMES);
        Files.writeString(file, xml("new"));
        registry.reload();
        assertThat(old.get("Item.img.xml").orElseThrow().getString("value")).hasValue("old");
        assertThat(registry.resource(WzResources.NAMES).get("Item.img.xml").orElseThrow().getString("value")).hasValue("new");
    }
    @Test public void partialPublishPinsCandidateAndRetriesOnlyFailedParticipant(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("String.wz")).resolve("Item.img.xml");
        Files.writeString(file, xml("one"));
        var registry = new WzResourceRegistry(root, List.of(new NameResourceLoader()), Runnable::run);
        AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean(true);
        var coordinator = new WzReloadCoordinator(registry, List.of(participant("a", first, new AtomicBoolean()), participant("b", second, fail)));
        Files.writeString(file, xml("two"));
        var result = coordinator.reload();
        assertThat(result.failures()).containsKey("b");
        assertThat(result.versions()).containsEntry("a", 2L).containsEntry("b", 1L);
        Files.writeString(file, xml("three"));
        assertThat(coordinator.prepare()).isEqualTo(result.digest());
        fail.set(false);
        var retry = coordinator.reload();
        assertThat(retry.version()).isEqualTo(2);
        assertThat(retry.digest()).isEqualTo(result.digest());
        assertThat(retry.failures()).isEmpty();
        assertThat(first.get()).isEqualTo(1);
        assertThat(second.get()).isEqualTo(1);
        assertThat(coordinator.reload(retry.digest())).isEqualTo(retry);
        assertThat(registry.resource(WzResources.NAMES).get("Item.img.xml").orElseThrow().getString("value")).hasValue("two");
        assertThat(coordinator.reload().version()).isEqualTo(3);
    }
    @Test public void prepareRetryReusesSnapshotAndExplicitDiscardAllowsSourceCorrection(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("String.wz")).resolve("Item.img.xml");
        Files.writeString(file, xml("one"));
        var registry = new WzResourceRegistry(root, List.of(new NameResourceLoader()), Runnable::run);
        var coordinator = new WzReloadCoordinator(registry, List.of());
        String prepared = coordinator.prepare();
        Files.writeString(file, xml("corrected"));
        assertThat(coordinator.prepare()).isEqualTo(prepared);
        assertThat(registry.version()).isEqualTo(1);
        coordinator.discardPrepared();
        String corrected = coordinator.prepare();
        assertThat(corrected).isNotEqualTo(prepared);
        assertThat(coordinator.reload(corrected).digest()).isEqualTo(corrected);
    }

    private WzReloadParticipant participant(String name, AtomicInteger count, AtomicBoolean fail) {
        return new WzReloadParticipant() {
            @Override public String name() { return name; }
            @Override public PreparedChange prepare(WzResourceRegistry.PreparedReload resources) {
                return () -> { if (fail.get()) throw new IllegalStateException("busy"); return count.incrementAndGet(); };
            }
        };
    }
    private String xml(String text) { return "<imgdir name=\"Item\"><string name=\"value\" value=\"" + text + "\"/></imgdir>"; }
}
