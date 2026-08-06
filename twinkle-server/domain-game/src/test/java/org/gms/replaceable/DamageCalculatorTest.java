package org.gms.replaceable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 伤害计算（纯函数）：物理公式 + 防御削减 + 边界。
 */
class DamageCalculatorTest {

    @Test
    @DisplayName("物理最大伤害公式：ceil((weaponMult×主属性+副属性)/100×watk)")
    void maxPhysicalFormula() {
        // (1.0×50 + 10)/100 × 10 = 6
        assertThat(DamageCalculator.maxPhysical(50, 10, 10, 1.0)).isEqualTo(6);
        // (1.2×50 + 10)/100 × 10 = 7
        assertThat(DamageCalculator.maxPhysical(50, 10, 10, 1.2)).isEqualTo(7);
        // (1.0×500 + 10)/100 × 10 = 51（ceil）
        assertThat(DamageCalculator.maxPhysical(500, 10, 10, 1.0)).isEqualTo(51);
    }

    @Test
    @DisplayName("实际伤害受防御削减，下限 1")
    void physicalDamageAppliesDefenseWithFloor() {
        assertThat(DamageCalculator.physicalDamage(500, 10, 10, 1.0, 0)).isEqualTo(51);
        // 51 - 50×0.5 = 26
        assertThat(DamageCalculator.physicalDamage(500, 10, 10, 1.0, 50)).isEqualTo(26);
        // 高防：削减后 < 1 → 1
        assertThat(DamageCalculator.physicalDamage(50, 10, 10, 1.0, 1000)).isEqualTo(1);
    }

    @Test
    @DisplayName("非法输入返回 0")
    void invalidInputsReturnZero() {
        assertThat(DamageCalculator.maxPhysical(-1, 10, 10, 1.0)).isZero();
        assertThat(DamageCalculator.maxPhysical(50, -1, 10, 1.0)).isZero();
        assertThat(DamageCalculator.maxPhysical(50, 10, -1, 1.0)).isZero();
    }
}
