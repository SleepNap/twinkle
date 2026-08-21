package org.gms.replaceable;

import org.gms.domain.game.map.MapleFoothold;
import org.gms.domain.game.map.MapleMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * parity 对照（M2-6）：本实现与参考项目（BeiDou-Server）公式/行为一致。
 *
 * <p>对照真值来自参考项目公开公式（思路参考，非逐字复制）：
 * <ul>
 *   <li>伤害：{@code ceil((weaponMult×主属性+副属性)/100 × wAtk)}（Character.calculateMaxBaseDamage）</li>
 *   <li>落点：整数线性插值（Foothold.calculateFooting）</li>
 * </ul>
 *
 * <p>真实录包回放需 v83 客户端素材，此处先做逻辑侧公式对照；录包回放框架（M0 基建）就绪待素材。
 */
class ParityReferenceTest {

    @Test
    @DisplayName("伤害公式对照参考项目：ceil((weaponMult×主+副)/100×wAtk)")
    void damageMatchesReferenceFormula() {
        // (1.0×50 + 10)/100 × 10 = 6
        assertThat(DamageCalculator.maxPhysical(50, 10, 10, 1.0)).isEqualTo(6);
        // (1.2×120 + 30)/100 × 25 = 43.5 → ceil 44
        assertThat(DamageCalculator.maxPhysical(120, 30, 25, 1.2)).isEqualTo(44);
        // (1.34×200 + 50)/100 × 30 = 95.4 → ceil 96
        assertThat(DamageCalculator.maxPhysical(200, 50, 30, 1.34)).isEqualTo(96);
        // 弓手（主 dex）：(1.0×60 + 10)/100 × 40 = 28
        assertThat(DamageCalculator.maxPhysical(10, 60, 40, 1.0)).isEqualTo(28);
    }

    @Test
    @DisplayName("落点公式对照参考项目：整数线性插值")
    void footingMatchesReferenceFormula() {
        MapleMap map = new MapleMap();
        MapleFoothold slope = new MapleFoothold(1, 0, 0);
        slope.setX1(0);
        slope.setY1(0);
        slope.setX2(100);
        slope.setY2(100);
        map.putFoothold(slope);
        MapleFoothold flat = new MapleFoothold(2, 0, 1);
        flat.setX1(200);
        flat.setY1(50);
        flat.setX2(300);
        flat.setY2(50);
        map.putFoothold(flat);

        // 斜线 (0,0)→(100,100)：x=50 → y=50；整数插值（参考公式 slope=1, intercept=0）
        assertThat(map.groundBelow(50, 0)).hasValue(50);
        // 水平线 y=50：直接返回 y2
        assertThat(map.groundBelow(250, 0)).hasValue(50);
        // x=150 无覆盖（两线间缝隙）→ empty
        assertThat(map.groundBelow(150, 0)).isEmpty();
    }

    @Test
    @DisplayName("落点：斜率为负的线段（参考整数插值）")
    void footingNegativeSlope() {
        MapleMap map = new MapleMap();
        MapleFoothold down = new MapleFoothold(1, 0, 0);
        down.setX1(100);
        down.setY1(100);
        down.setX2(200);
        down.setY2(0);
        map.putFoothold(down);

        // (100,100)→(200,0)：slope=(100-0)/(100-200)=-1, intercept=200；x=150 → 50
        assertThat(map.groundBelow(150, 0)).hasValue(50);
    }
}
