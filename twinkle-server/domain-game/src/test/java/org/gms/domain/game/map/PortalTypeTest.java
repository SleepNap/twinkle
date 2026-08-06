package org.gms.domain.game.map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 传送点类型映射（v83 字节值）+ 传送点/刷怪点纯数据字段。
 */
class PortalTypeTest {

    @Test
    @DisplayName("v83 字节值 → 类型")
    void typeMapping() {
        assertThat(PortalType.fromType((byte) 0)).isEqualTo(PortalType.MAP_PORTAL);
        assertThat(PortalType.fromType((byte) 1)).isEqualTo(PortalType.DOOR);
        assertThat(PortalType.fromType((byte) 3)).isEqualTo(PortalType.CHANGE);
        assertThat(PortalType.fromType((byte) 8)).isEqualTo(PortalType.SCRIPT);
        assertThat(PortalType.fromType((byte) 11)).isEqualTo(PortalType.SPRING);
    }

    @Test
    @DisplayName("未知字节值 → UNKNOWN，不回崩")
    void unknownTypeFallsBack() {
        assertThat(PortalType.fromType((byte) 99)).isEqualTo(PortalType.UNKNOWN);
        assertThat(PortalType.fromType((byte) -1)).isEqualTo(PortalType.UNKNOWN);
    }

    @Test
    @DisplayName("Portal 字段 set/get")
    void portalFields() {
        Portal p = new Portal(2, "out00", PortalType.MAP_PORTAL, 50, 100);
        p.setTargetMapId(200000000);
        p.setTargetPortalName("in00");

        assertThat(p.getId()).isEqualTo(2);
        assertThat(p.getName()).isEqualTo("out00");
        assertThat(p.getType()).isEqualTo(PortalType.MAP_PORTAL);
        assertThat(p.getX()).isEqualTo(50);
        assertThat(p.getY()).isEqualTo(100);
        assertThat(p.getTargetMapId()).isEqualTo(200000000);
        assertThat(p.getTargetPortalName()).isEqualTo("in00");
    }

    @Test
    @DisplayName("SpawnPoint 字段")
    void spawnPointFields() {
        SpawnPoint s = new SpawnPoint(100100, 10, 20);
        s.setRespawnInterval(5000);
        s.setChance(50);

        assertThat(s.getMonsterId()).isEqualTo(100100);
        assertThat(s.getX()).isEqualTo(10);
        assertThat(s.getY()).isEqualTo(20);
        assertThat(s.getRespawnInterval()).isEqualTo(5000);
        assertThat(s.getChance()).isEqualTo(50);
    }
}
