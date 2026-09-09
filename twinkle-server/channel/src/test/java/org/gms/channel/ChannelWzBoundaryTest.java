package org.gms.channel;

import org.gms.concurrent.GameExecution;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.resource.MapResourceLoader;
import org.gms.wz.resource.MobResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 显式把业务操作插入 WZ 准备/发布之间，验证频道提交边界。 */
public class ChannelWzBoundaryTest {
    @Test public void channelsAdoptResourcesWithTheirLiveObjectsIncludingLateSpawns(@TempDir Path root) throws Exception {
        Path firstFile = mapFile(root, 100000000), lateFile = mapFile(root, 100000001);
        Path mobFile = Files.createDirectories(root.resolve("Mob.wz")).resolve("0100100.img.xml");
        Files.writeString(firstFile, mapXml(false)); Files.writeString(lateFile, mapXml(false));
        Files.writeString(mobFile, mobXml(100));
        var resources = new WzResourceRegistry(root, List.of(new MapResourceLoader(), new MobResourceLoader()), Runnable::run);
        try (var first = new GameExecution("wz-first", new DefaultVersionGate());
             var second = new GameExecution("wz-second", new DefaultVersionGate())) {
            var a = new ChannelMapManager(resources, 1, first); var b = new ChannelMapManager(resources, 2, second);
            var initial = a.getMap(100000000); b.getMap(100000000);
            Files.writeString(firstFile, mapXml(true)); Files.writeString(lateFile, mapXml(true));
            Files.writeString(mobFile, mobXml(50));
            var prepared = resources.prepareReload();
            var publishA = a.prepare(prepared); var publishB = b.prepare(prepared);
            // 此怪物与地图均在 prepare 后出现，旧实现会漏掉它们。
            var lateMob = first.call(() -> {
                var mob = new MapleMonster(resources.mob(100100));
                a.getMap(100000001).addMonster(mob); return mob;
            });
            resources.commit(prepared);
            assertThat(first.call(() -> resources.mob(100100).getMaxHp())).isEqualTo(100);
            assertThat(second.call(resources::version)).isEqualTo(1);
            publishA.publish();
            assertThat(first.call(resources::version)).isEqualTo(2);
            assertThat(first.call(() -> resources.mob(100100).getMaxHp())).isEqualTo(50);
            assertThat(first.call(lateMob::getHp)).isEqualTo(50);
            assertThat(first.call(initial::isTown)).isTrue();
            assertThat(second.call(resources::version)).isEqualTo(1);
            assertThat(second.call(() -> b.getMap(100000000).isTown())).isFalse();
            publishB.publish();
            assertThat(second.call(resources::version)).isEqualTo(2);
        }
    }

    @Test public void lateValidationFailureKeepsTheChannelOnItsPreviousGeneration(@TempDir Path root) throws Exception {
        Path file = mapFile(root, 100000000), missing = mapFile(root, 100000001);
        Path mobFile = Files.createDirectories(root.resolve("Mob.wz")).resolve("0100100.img.xml");
        Files.writeString(file, mapXml(false)); Files.writeString(missing, mapXml(false));
        Files.writeString(mobFile, mobXml(100));
        var resources = new WzResourceRegistry(root, List.of(new MapResourceLoader(), new MobResourceLoader()), Runnable::run);
        try (var execution = new GameExecution("wz-failure", new DefaultVersionGate())) {
            var manager = new ChannelMapManager(resources, 1, execution); manager.getMap(100000000);
            Files.writeString(file, mapXml(true)); Files.writeString(mobFile, mobXml(50));
            var prepared = resources.prepareReload(); var change = manager.prepare(prepared);
            manager.getMap(100000001);
            Files.delete(missing);
            resources.commit(prepared);
            assertThatThrownBy(change::publish).isInstanceOf(IllegalArgumentException.class);
            assertThat(manager.resourceVersion()).isEqualTo(1);
            assertThat(execution.call(resources::version)).isEqualTo(1);
            assertThat(execution.call(() -> resources.mob(100100).getMaxHp())).isEqualTo(100);
            assertThat(execution.call(() -> manager.getMap(100000000).isTown())).isFalse();
        }
    }

    private static Path mapFile(Path root, int id) throws Exception {
        return Files.createDirectories(root.resolve("Map.wz/Map/Map1")).resolve(id + ".img.xml");
    }
    private static String mapXml(boolean town) {
        return "<imgdir name=\"map\"><imgdir name=\"info\"><int name=\"town\" value=\""
                + (town ? 1 : 0) + "\"/></imgdir></imgdir>";
    }
    private static String mobXml(int hp) {
        return "<imgdir name=\"0100100.img\"><imgdir name=\"info\"><int name=\"maxHP\" value=\""
                + hp + "\"/></imgdir></imgdir>";
    }
}
