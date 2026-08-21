package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.wz.WzResourceRegistry;
import org.gms.wz.WzReloadCoordinator;
import org.gms.wz.resource.MapResourceLoader;
import org.gms.wz.resource.MobResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelMapManagerReloadTest {

    @Test
    void reloadReplacesStaticWzDataButKeepsRuntimeMapIdentityAndCharacters(@TempDir Path root) throws Exception {
        Path file = Files.createDirectories(root.resolve("Map.wz/Map/Map1"))
                .resolve("100000000.img.xml");
        Files.writeString(file, mapXml(false, 100000001, 100100));
        Path mobFile = Files.createDirectories(root.resolve("Mob.wz")).resolve("0100100.img.xml");
        Files.writeString(mobFile, mobXml(100));
        WzResourceRegistry resources = new WzResourceRegistry(root,
                List.of(new MapResourceLoader(), new MobResourceLoader()), Runnable::run);
        ChannelMapManager manager = new ChannelMapManager(resources);
        WzReloadCoordinator coordinator = new WzReloadCoordinator(resources, List.of(manager));
        MapleMap liveMap = manager.getMap(100000000);
        Character character = new Character(resources.version());
        liveMap.addCharacter(character);
        MapleMonster monster = new MapleMonster(resources.mob(100100));
        monster.takeDamage(25);
        liveMap.addMonster(monster);

        Files.writeString(file, mapXml(true, 100000002, 100101));
        Files.writeString(mobFile, mobXml(50));
        WzReloadCoordinator.ReloadReport report = coordinator.reload();

        assertThat(manager.getMap(100000000)).isSameAs(liveMap);
        assertThat(liveMap.isTown()).isTrue();
        assertThat(liveMap.getReturnMapId()).isEqualTo(100000002);
        assertThat(liveMap.spawnPoints()).singleElement()
                .satisfies(spawn -> assertThat(spawn.getMonsterId()).isEqualTo(100101));
        assertThat(liveMap.characters()).containsExactly(character);
        assertThat(liveMap.monsters()).containsExactly(monster);
        assertThat(monster.getData().getMaxHp()).isEqualTo(50);
        assertThat(monster.getHp()).isEqualTo(50);
        assertThat(report.runtimeObjects()).containsEntry("channel-maps", 2);
    }

    private static String mapXml(boolean town, int returnMap, int monsterId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><imgdir name=\"100000000.img\">"
                + "<imgdir name=\"info\"><int name=\"town\" value=\"" + (town ? 1 : 0)
                + "\"/><int name=\"returnMap\" value=\"" + returnMap + "\"/></imgdir>"
                + "<imgdir name=\"life\"><imgdir name=\"0\"><string name=\"type\" value=\"m\"/>"
                + "<string name=\"id\" value=\"" + monsterId + "\"/><int name=\"x\" value=\"1\"/>"
                + "<int name=\"y\" value=\"2\"/></imgdir></imgdir></imgdir>";
    }

    private static String mobXml(int maxHp) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><imgdir name=\"0100100.img\">"
                + "<imgdir name=\"info\"><int name=\"maxHP\" value=\"" + maxHp
                + "\"/><int name=\"maxMP\" value=\"10\"/></imgdir></imgdir>";
    }
}
