package org.gms.replaceable;

import org.gms.domain.game.Character;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生命恢复系统：状态/逻辑分离 + 版本门落地。
 * 系统只经 CharacterState 接口操作角色（Character 是稳定层具体类，仅在测试装配侧 new）。
 */
class HealthRecoverySystemTest {

    @Test
    @DisplayName("恢复 HP 向 maxHp 收敛并封顶")
    void recoversHpTowardMaxHpCapped() {
        VersionGate gate = new DefaultVersionGate();
        Character c = new Character(gate.currentVersion());
        c.setMaxHp(1000);
        c.setHp(100);
        HealthRecoverySystem system = new HealthRecoverySystem(gate);

        assertThat(system.tick(c)).isTrue();
        assertThat(c.getHp()).isEqualTo(120); // ceil(1000 / 50) = 20

        for (int i = 0; i < 100 && c.getHp() < c.getMaxHp(); i++) {
            system.tick(c);
        }
        assertThat(c.getHp()).isEqualTo(1000); // 封顶 maxHp，不溢出
    }

    @Test
    @DisplayName("满血时不改动")
    void atFullHpNoChange() {
        VersionGate gate = new DefaultVersionGate();
        Character c = new Character(gate.currentVersion());
        c.setMaxHp(500);
        c.setHp(500);
        HealthRecoverySystem system = new HealthRecoverySystem(gate);

        assertThat(system.tick(c)).isTrue();
        assertThat(c.getHp()).isEqualTo(500);
    }

    @Test
    @DisplayName("重载换代后旧逻辑迟到写被版本门拒绝")
    void staleWriteRejectedAfterReload() {
        VersionGate gate = new DefaultVersionGate();
        Character c = new Character(gate.currentVersion()); // 逻辑版本 1
        c.setMaxHp(1000);
        c.setHp(100);
        HealthRecoverySystem system = new HealthRecoverySystem(gate);

        gate.onReload(); // L3 换代 → 版本 2，角色成为旧逻辑产物

        assertThat(system.tick(c)).isFalse(); // 迟到写被拒
        assertThat(c.getHp()).isEqualTo(100); // 状态未被旧逻辑改动
    }
}
