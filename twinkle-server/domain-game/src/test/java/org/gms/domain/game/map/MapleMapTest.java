package org.gms.domain.game.map;

import org.gms.domain.game.Character;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 地图纯数据：玩家进出、传送点/刷怪点容器、静态属性。
 */
class MapleMapTest {

    private Character newChar() {
        return new Character(new DefaultVersionGate().currentVersion());
    }

    @Test
    @DisplayName("玩家进出与人数")
    void characterEnterLeave() {
        MapleMap map = new MapleMap();
        Character a = newChar();
        Character b = newChar();

        map.addCharacter(a);
        map.addCharacter(b);
        assertThat(map.characterCount()).isEqualTo(2);
        assertThat(map.characters()).containsExactly(a, b);

        map.removeCharacter(a);
        assertThat(map.characterCount()).isEqualTo(1);
        assertThat(map.characters()).containsExactly(b);
    }

    @Test
    @DisplayName("传送点 put/get 与不可变视图")
    void portalPutGet() {
        MapleMap map = new MapleMap();
        Portal p = new Portal(1, "out00", PortalType.MAP_PORTAL, 50, 100);
        map.putPortal(p);

        assertThat(map.getPortal(1)).isSameAs(p);
        assertThat(map.portals()).containsExactly(p);
    }

    @Test
    @DisplayName("刷怪点集合")
    void spawnPointList() {
        MapleMap map = new MapleMap();
        SpawnPoint s1 = new SpawnPoint(100100, 10, 20);
        SpawnPoint s2 = new SpawnPoint(100101, 30, 40);
        map.addSpawnPoint(s1);
        map.addSpawnPoint(s2);

        assertThat(map.spawnPoints()).containsExactly(s1, s2);
    }

    @Test
    @DisplayName("静态属性 set/get 往返")
    void staticFieldsRoundTrip() {
        MapleMap map = new MapleMap();
        map.setMapId(100000000);
        map.setReturnMapId(100000000);
        map.setTown(true);
        map.setFieldLimit(0x80);
        map.setMonsterRate(2);
        map.setMobCapacity(10);
        map.setOnUserEnter("onUserEnter");

        assertThat(map.getMapId()).isEqualTo(100000000);
        assertThat(map.getReturnMapId()).isEqualTo(100000000);
        assertThat(map.isTown()).isTrue();
        assertThat(map.getFieldLimit()).isEqualTo(0x80);
        assertThat(map.getMonsterRate()).isEqualTo(2);
        assertThat(map.getMobCapacity()).isEqualTo(10);
        assertThat(map.getOnUserEnter()).isEqualTo("onUserEnter");
    }

    @Test
    @DisplayName("玩家列表是不可变视图")
    void charactersViewIsImmutable() {
        MapleMap map = new MapleMap();
        map.addCharacter(newChar());

        assertThatThrownBy(() -> map.characters().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
