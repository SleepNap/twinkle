package org.gms.domain.game;

import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 角色纯数据模型：v83 默认值、Versioned 契约、背包懒创建、技能集合。
 */
class CharacterTest {

    @Test
    @DisplayName("新角色带 v83 默认属性")
    void newCharacterHasV83Defaults() {
        Character c = new Character(1);

        assertThat(c.getLevel()).isEqualTo(1);
        assertThat(c.getStr()).isEqualTo((short) 12);
        assertThat(c.getDex()).isEqualTo((short) 5);
        assertThat(c.getLuk()).isEqualTo((short) 4);
        assertThat(c.getHp()).isEqualTo(50);
        assertThat(c.getMaxHp()).isEqualTo(50);
        assertThat(c.getMp()).isEqualTo(5);
        assertThat(c.getJob()).isEqualTo(0);
        assertThat(c.getSp()).isEqualTo("0,0,0,0,0,0,0,0,0,0");
        assertThat(c.getBuddyCapacity()).isEqualTo(25);
    }

    @Test
    @DisplayName("实现 Versioned：logicVersion 来自版本门当前版本")
    void characterCarriesLogicVersionFromGate() {
        VersionGate gate = new DefaultVersionGate();
        Character c = new Character(gate.currentVersion());

        assertThat(c.logicVersion()).isEqualTo(1L);
        assertThat(gate.decide(c)).isEqualTo(org.gms.hotreload.versioned.VersionDecision.ALLOW);

        // 换代后旧角色成为迟到写（架构 5.3：旧逻辑写被版本门识别）
        gate.onReload();
        assertThat(gate.decide(c)).isEqualTo(org.gms.hotreload.versioned.VersionDecision.STALE);
    }

    @Test
    @DisplayName("背包按类型懒创建，槽位上限取持久化槽位字段")
    void inventoriesCreatedLazilyWithSlotLimit() {
        Character c = new Character(1);
        c.setUseSlots(40);

        assertThat(c.getInventory(InventoryType.USE).getSlotLimit()).isEqualTo(40);
        assertThat(c.getInventory(InventoryType.EQUIP).getSlotLimit()).isEqualTo(24);
        // 同一类型是同一实例
        assertThat(c.getInventory(InventoryType.USE)).isSameAs(c.getInventory(InventoryType.USE));
    }

    @Test
    @DisplayName("技能增删查")
    void skillPutGetRemove() {
        Character c = new Character(1);
        c.putSkill(new SkillEntry(1001, 5));
        c.putSkill(new SkillEntry(1002, 1, 12345L));

        assertThat(c.getSkill(1001)).isEqualTo(new SkillEntry(1001, 5));
        assertThat(c.getSkill(1002).expiration()).isEqualTo(12345L);
        assertThat(c.skills()).containsKeys(1001, 1002);

        c.removeSkill(1001);
        assertThat(c.getSkill(1001)).isNull();
    }

    @Test
    @DisplayName("持久化字段 set/get 往返")
    void persistedFieldsRoundTrip() {
        Character c = new Character(1);
        c.setId(42L);
        c.setName("Hero");
        c.setMap(100000000);
        c.setSpawnPoint(2);
        c.setMeso(1000000);
        c.setHair(30000);

        assertThat(c.getId()).isEqualTo(42L);
        assertThat(c.getName()).isEqualTo("Hero");
        assertThat(c.getMap()).isEqualTo(100000000);
        assertThat(c.getSpawnPoint()).isEqualTo(2);
        assertThat(c.getMeso()).isEqualTo(1000000);
        assertThat(c.getHair()).isEqualTo(30000);
    }
}
