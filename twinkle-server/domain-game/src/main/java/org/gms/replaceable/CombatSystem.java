package org.gms.replaceable;

import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.spi.CharacterState;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

/**
 * 战斗系统（可替换层，架构第三节状态/逻辑分离 + 红线 8/11/12）。
 *
 * <p>伤害计算在 {@link DamageCalculator}（纯函数），本系统负责编排（版本门 + 扣血）。
 * 经 {@link CharacterState} 接口读攻击者属性，不引用其具体类（ArchUnit 规则 3）；
 * 目标 {@link MapleMonster} 属稳定层数据对象（mob 包，不在规则 3 禁止列表）。
 */
public final class CombatSystem {

    /** 未装备武器的默认物理攻击力（v83 徒手 watk 基数）。 */
    public static final int BARE_HAND_WATK = 1;

    private final VersionGate versionGate;

    public CombatSystem(VersionGate versionGate) {
        this.versionGate = versionGate;
    }

    /**
     * 玩家物理攻击怪物。
     *
     * @param attacker 攻击方（经 spi 接口）
     * @param target   目标怪物（稳定层数据对象）
     * @param watk     武器攻击力（装备系统落地前由调用方给；徒手用 {@link #BARE_HAND_WATK}）
     */
    public DamageResult physicalAttack(CharacterState attacker, MapleMonster target, int watk) {
        if (versionGate.decide(attacker) != VersionDecision.ALLOW) {
            return DamageResult.blocked();
        }
        int damage = DamageCalculator.physicalDamage(
                attacker.getStr(), attacker.getDex(), watk, 1.0, target.getData().getPdd());
        target.takeDamage(damage);
        return new DamageResult(damage, target.isAlive());
    }

    /** 攻击结果。 */
    public record DamageResult(int damage, boolean targetAlive) {

        /** 版本门拒绝/无效攻击的占位结果。 */
        public static DamageResult blocked() {
            return new DamageResult(0, true);
        }
    }
}
