package org.gms.wz;

import org.gms.domain.game.map.MapleFoothold;
import org.gms.domain.game.map.MapleMap;
import org.gms.domain.game.map.PortalType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地图加载：WZ 目录（架构 6.4 配置直接指定）→ MapleMap 填充 info/portal/life。
 * 用内嵌测试资源（src/test/resources/wz/），不依赖外部北斗目录。
 */
class MapLoaderTest {

    private static final Path WZ_ROOT = resourceRoot();

    private static Path resourceRoot() {
        try {
            return Path.of(Objects.requireNonNull(
                    MapLoaderTest.class.getResource("/wz")).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("测试资源定位失败", e);
        }
    }

    @Test
    @DisplayName("填充 info / portal / 怪物刷新点")
    void loadsMapInfoPortalsAndSpawns() {
        MapleMap map = new MapLoader(WZ_ROOT).load(100000000).orElseThrow();

        // info
        assertThat(map.getMapId()).isEqualTo(100000000);
        assertThat(map.isTown()).isTrue();
        assertThat(map.getReturnMapId()).isEqualTo(100000000);
        assertThat(map.getForcedReturnMap()).isEqualTo(999_999_999);
        assertThat(map.getMonsterRate()).isEqualTo(1);
        assertThat(map.getOnUserEnter()).isEqualTo("explorationPoint");

        // portal
        assertThat(map.getPortal(0).getName()).isEqualTo("sp");
        assertThat(map.getPortal(0).getType()).isEqualTo(PortalType.MAP_PORTAL);
        assertThat(map.getPortal(1).getType()).isEqualTo(PortalType.CHANGE);
        assertThat(map.getPortal(1).getTargetMapId()).isEqualTo(104000000);
        assertThat(map.getPortal(1).getTargetPortalName()).isEqualTo("in00");

        // life：只有 m（怪物）建 SpawnPoint，n（NPC）忽略
        assertThat(map.spawnPoints()).hasSize(1);
        assertThat(map.spawnPoints().get(0).getMonsterId()).isEqualTo(100100);
        assertThat(map.spawnPoints().get(0).getX()).isEqualTo(500);
        assertThat(map.spawnPoints().get(0).getRespawnInterval()).isEqualTo(5000);
    }

    @Test
    @DisplayName("填充 foothold：多 layer/group、prev/next、force")
    void loadsFootholds() {
        MapleMap map = new MapLoader(WZ_ROOT).load(100000000).orElseThrow();
        assertThat(map.footholds()).hasSize(4);

        // layer 0 / group 0：两条链段，1→2（prev/next 串链）
        MapleFoothold ground1 = foothold(map, 0, 0, 1);
        assertThat(ground1.getX1()).isEqualTo(10);
        assertThat(ground1.getY1()).isEqualTo(100);
        assertThat(ground1.getX2()).isEqualTo(200);
        assertThat(ground1.getY2()).isEqualTo(100);
        assertThat(ground1.getNext()).isEqualTo(2);
        assertThat(ground1.getPrev()).isEqualTo(-1); // 无 prev 默认 -1

        MapleFoothold ground2 = foothold(map, 0, 0, 2);
        assertThat(ground2.getPrev()).isEqualTo(1);
        assertThat(ground2.getNext()).isEqualTo(-1);

        // layer 0 / group 1：force 字段
        MapleFoothold forced = map.footholds().stream()
                .filter(f -> f.getLayer() == 0 && f.getGroup() == 1)
                .findFirst().orElseThrow();
        assertThat(forced.getForce()).isEqualTo(50);

        // layer 1：独立层
        MapleFoothold top = map.footholds().stream()
                .filter(f -> f.getLayer() == 1)
                .findFirst().orElseThrow();
        assertThat(top.getGroup()).isZero();
        assertThat(top.getY1()).isZero();
    }

    private static MapleFoothold foothold(MapleMap map, int layer, int group, int id) {
        return map.footholds().stream()
                .filter(f -> f.getLayer() == layer && f.getGroup() == group && f.getId() == id)
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("地图文件不存在 → empty（调用方决定报错）")
    void missingMapReturnsEmpty() {
        assertThat(new MapLoader(WZ_ROOT).load(999_999_999)).isEmpty();
    }
}
