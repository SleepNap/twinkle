package org.gms.domain.game.logic;

import org.gms.domain.game.spi.MonsterState;
import org.gms.domain.game.spi.CharacterState;

/** CombatSystem 的稳定调用契约；实现由逻辑包提供。 */
public interface CombatSystem {
    public static final int BARE_HAND_WATK = 1;

    public DamageResult physicalAttack(CharacterState attacker, MonsterState target, int wAtk);

    public record DamageResult(int damage, boolean targetAlive, boolean killed) {

        /** 版本门拒绝/无效攻击的占位结果。 */
        public static DamageResult blocked() {
            return new DamageResult(0, true, false);
        }
    }
}
