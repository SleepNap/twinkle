package org.gms.replaceable;

import org.gms.domain.game.Character;
import org.gms.domain.game.map.MapleFoothold;
import org.gms.domain.game.map.MapleMap;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 移动系统：位置更新 + foothold 落地物理 + 版本门。
 */
class MovementSystemTest {

    private final VersionGate versionGate = new DefaultVersionGate();
    private final MovementSystem movement = new MovementSystem(versionGate);

    /** 水平地面 y=100（x∈[0,200]）+ 高台 y=50（x∈[300,400]）。 */
    private static MapleMap map() {
        MapleMap map = new MapleMap();
        MapleFoothold ground = new MapleFoothold(1, 0, 0);
        ground.setX1(0);
        ground.setY1(100);
        ground.setX2(200);
        ground.setY2(100);
        map.putFoothold(ground);
        MapleFoothold platform = new MapleFoothold(2, 0, 1);
        platform.setX1(300);
        platform.setY1(50);
        platform.setX2(400);
        platform.setY2(50);
        map.putFoothold(platform);
        return map;
    }

    @Test
    @DisplayName("水平移动更新 x，落地到脚下地面")
    void moveUpdatesPositionAndLands() {
        Character chr = new Character(versionGate.currentVersion());
        MapleMap map = map();

        assertThat(movement.move(chr, map, 50, 0)).isTrue();
        assertThat(chr.getX()).isEqualTo(50);
        assertThat(chr.getY()).isEqualTo(100);     // 落地面

        assertThat(movement.move(chr, map, 350, 0)).isTrue();
        assertThat(chr.getX()).isEqualTo(350);
        assertThat(chr.getY()).isEqualTo(50);      // 落高台
    }

    @Test
    @DisplayName("悬空（无覆盖地面）保持 newY")
    void hoverWithoutGroundKeepsNewY() {
        Character chr = new Character(versionGate.currentVersion());
        MapleMap map = map();

        assertThat(movement.move(chr, map, 250, 30)).isTrue();
        assertThat(chr.getX()).isEqualTo(250);     // 两地面之间的缝隙
        assertThat(chr.getY()).isEqualTo(30);      // 无地面，保持 newY
    }

    @Test
    @DisplayName("版本门拒绝换代后的迟到移动")
    void versionGateBlocksStaleMove() {
        Character chr = new Character(versionGate.currentVersion());
        MapleMap map = map();

        versionGate.onReload();
        assertThat(movement.move(chr, map, 50, 0)).isFalse();
        assertThat(chr.getX()).isZero();
        assertThat(chr.getY()).isZero();
    }
}
