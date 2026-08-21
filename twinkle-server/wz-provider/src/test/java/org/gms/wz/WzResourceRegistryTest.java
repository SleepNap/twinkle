package org.gms.wz;

import org.gms.wz.resource.BuffResourceLoader;
import org.gms.wz.resource.ItemResourceLoader;
import org.gms.wz.resource.MapResourceLoader;
import org.gms.wz.resource.MobResourceLoader;
import org.gms.wz.resource.NameResourceLoader;
import org.gms.wz.resource.QuestResourceLoader;
import org.gms.wz.resource.SkillResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WzResourceRegistryTest {

    @Test
    void registeredLoaderAutomaticallyParticipatesInReload(@TempDir Path root) {
        WzResourceKey<String> custom = new WzResourceKey<>("custom");
        AtomicInteger source = new AtomicInteger(1);
        WzResourceLoader<String> loader = new WzResourceLoader<>() {
            @Override
            public WzResourceKey<String> key() {
                return custom;
            }

            @Override
            public String load(Path wzRoot) {
                return "value-" + source.get();
            }

            @Override
            public int entryCount(String resource) {
                return 1;
            }
        };

        WzResourceRegistry registry = new WzResourceRegistry(root, List.of(loader), Runnable::run);
        assertThat(registry.resource(custom)).isEqualTo("value-1");

        source.set(2);
        WzResourceRegistry.ReloadReport report = registry.reload();

        assertThat(registry.resource(custom)).isEqualTo("value-2");
        assertThat(report.version()).isEqualTo(2);
        assertThat(report.resources()).containsEntry("custom", 1);
    }

    @Test
    void failedReloadKeepsEntirePreviousSnapshot(@TempDir Path root) {
        WzResourceKey<String> stable = new WzResourceKey<>("stable");
        WzResourceKey<String> failing = new WzResourceKey<>("failing");
        AtomicInteger source = new AtomicInteger(1);
        WzResourceLoader<String> stableLoader = loader(stable, source, false);
        WzResourceLoader<String> failingLoader = loader(failing, source, true);
        WzResourceRegistry registry = new WzResourceRegistry(
                root, List.of(stableLoader, failingLoader), Runnable::run);

        source.set(2);
        assertThatThrownBy(registry::reload).isInstanceOf(IllegalStateException.class);

        assertThat(registry.version()).isEqualTo(1);
        assertThat(registry.resource(stable)).isEqualTo("value-1");
        assertThat(registry.resource(failing)).isEqualTo("value-1");
    }

    @Test
    void failedRuntimePreparationKeepsResourceSnapshotAndSkipsAllPublishes(@TempDir Path root) {
        WzResourceKey<String> key = new WzResourceKey<>("stable");
        AtomicInteger source = new AtomicInteger(1);
        AtomicInteger published = new AtomicInteger();
        WzResourceRegistry registry = new WzResourceRegistry(
                root, List.of(loader(key, source, false)), Runnable::run);
        WzReloadParticipant accepted = new WzReloadParticipant() {
            @Override public String name() { return "accepted"; }
            @Override public PreparedChange prepare(WzResourceRegistry.PreparedReload resources) {
                return published::incrementAndGet;
            }
        };
        WzReloadParticipant rejected = new WzReloadParticipant() {
            @Override public String name() { return "rejected"; }
            @Override public PreparedChange prepare(WzResourceRegistry.PreparedReload resources) {
                throw new IllegalStateException("invalid runtime projection");
            }
        };
        WzReloadCoordinator coordinator = new WzReloadCoordinator(registry, List.of(accepted, rejected));

        source.set(2);
        assertThatThrownBy(coordinator::reload).isInstanceOf(IllegalStateException.class);

        assertThat(registry.version()).isEqualTo(1);
        assertThat(registry.resource(key)).isEqualTo("value-1");
        assertThat(published).hasValue(0);
    }

    @Test
    void nodeCatalogCacheIsReplacedOnGlobalReload(@TempDir Path root) throws Exception {
        Path stringWz = Files.createDirectories(root.resolve("String.wz"));
        Path file = stringWz.resolve("Item.img.xml");
        Files.writeString(file, xml("old"));
        WzResourceRegistry registry = new WzResourceRegistry(
                root, List.of(new NameResourceLoader()), Runnable::run);

        WzNodeCatalog oldCatalog = registry.resource(WzResources.NAMES);
        assertThat(oldCatalog.get("Item.img.xml").orElseThrow().getString("value")).hasValue("old");

        Files.writeString(file, xml("new"));
        assertThat(oldCatalog.get("Item.img.xml").orElseThrow().getString("value")).hasValue("old");

        registry.reload();
        WzNodeCatalog newCatalog = registry.resource(WzResources.NAMES);
        assertThat(newCatalog).isNotSameAs(oldCatalog);
        assertThat(newCatalog.get("Item.img.xml").orElseThrow().getString("value")).hasValue("new");
    }

    @Test
    void builtInResourceSetCoversCurrentAndFutureGameDataDomains(@TempDir Path root) {
        WzResourceRegistry registry = new WzResourceRegistry(root, List.of(
                new ItemResourceLoader(), new MobResourceLoader(), new MapResourceLoader(),
                new NameResourceLoader(), new SkillResourceLoader(), new BuffResourceLoader(),
                new QuestResourceLoader()), Runnable::run);

        assertThat(registry.status().resources().keySet())
                .containsExactlyInAnyOrder("items", "mobs", "maps", "names", "skills", "buffs", "quests");
        assertThat(registry.resource(WzResources.SKILLS).root()).isEqualTo(root.resolve("Skill.wz").toAbsolutePath());
        assertThat(registry.resource(WzResources.BUFFS).root()).isEqualTo(root.resolve("Skill.wz").toAbsolutePath());
        assertThat(registry.resource(WzResources.QUESTS).root()).isEqualTo(root.resolve("Quest.wz").toAbsolutePath());
    }

    @Test
    void gameDataQueriesObserveNewItemSnapshotAfterReload(@TempDir Path root) throws Exception {
        Path consume = Files.createDirectories(root.resolve("Item.wz/Consume"));
        Path file = consume.resolve("0200.img.xml");
        Files.writeString(file, itemXml(50));
        WzResourceRegistry registry = new WzResourceRegistry(root,
                List.of(new ItemResourceLoader(), new MobResourceLoader()), Runnable::run);

        assertThat(registry.item(2_000_000).getStat("hp")).isEqualTo(50);

        Files.writeString(file, itemXml(500));
        registry.reload();

        assertThat(registry.item(2_000_000).getStat("hp")).isEqualTo(500);
    }

    private static WzResourceLoader<String> loader(WzResourceKey<String> key,
                                                    AtomicInteger source,
                                                    boolean failOnSecondVersion) {
        return new WzResourceLoader<>() {
            @Override
            public WzResourceKey<String> key() {
                return key;
            }

            @Override
            public String load(Path wzRoot) {
                if (failOnSecondVersion && source.get() == 2) {
                    throw new IllegalStateException("broken resource");
                }
                return "value-" + source.get();
            }
        };
    }

    private static String xml(String value) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><imgdir name=\"root\">"
                + "<string name=\"value\" value=\"" + value + "\"/></imgdir>";
    }

    private static String itemXml(int hp) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><imgdir name=\"0200.img\">"
                + "<imgdir name=\"02000000\"><imgdir name=\"info\"><int name=\"slotMax\" value=\"100\"/>"
                + "</imgdir><imgdir name=\"spec\"><int name=\"hp\" value=\"" + hp
                + "\"/></imgdir></imgdir></imgdir>";
    }
}
