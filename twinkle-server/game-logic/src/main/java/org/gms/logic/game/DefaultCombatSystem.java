package org.gms.logic.game;

import org.gms.domain.game.logic.*;

import org.gms.domain.game.spi.MonsterState;
import org.gms.domain.game.spi.CharacterState;
import org.gms.hotreload.versioned.VersionDecision;
import org.gms.hotreload.versioned.VersionGate;

/**
 * 战斗系统（可替换层，架构第三节状态/逻辑分离 + 红线 8/11/12）。
 *
 * <p>伤害计算在 {@link DamageCalculator}（纯函数），本系统负责编排（版本门 + 扣血）。
 * 经 {@link CharacterState} 接口读攻击者属性，不引用其具体类（ArchUnit 规则 3）；
 * 目标 {@link MonsterState} 属稳定层战斗状态契约。
 */
public final class DefaultCombatSystem implements CombatSystem {

    /** 未装备武器的默认物理攻击力（v83 徒手 wAtk 基数）。 */

    private final VersionGate versionGate;

    public DefaultCombatSystem(VersionGate versionGate) {
        this.versionGate = versionGate;
    }

    /**
     * 玩家物理攻击怪物。
     *
     * @param attacker 攻击方（经 spi 接口）
     * @param target   目标怪物（稳定层数据对象）
     * @param wAtk     装备攻击力（未提供加成时使用 {@link #BARE_HAND_WATK}）
     */
    public DamageResult physicalAttack(CharacterState attacker, MonsterState target, int wAtk) {
        if (versionGate.decide(attacker) != VersionDecision.ALLOW) {
            return DamageResult.blocked();
        }
        int damage = DamageCalculator.physicalDamage(
                attacker.totalStr(), attacker.totalDex(), wAtk, 1.0, target.physicalDefense());
        var outcome = target.applyDamage(damage);
        return new DamageResult(outcome.damage(), outcome.alive(), outcome.killed());
    }

    /** 攻击结果。 */

}
