package org.gms.replaceable;

import org.gms.domain.game.Character;
import org.gms.domain.game.mob.MapleMonster;
import org.gms.domain.game.mob.MobData;
import org.gms.hotreload.versioned.DefaultVersionGate;
import org.gms.hotreload.versioned.VersionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 战斗系统：玩家物理攻击怪物（伤害计算 + 扣血 + 版本门）。
 */
class CombatSystemTest {

    private final VersionGate versionGate = new DefaultVersionGate();
    private final CombatSystem combat = new CombatSystem(versionGate);

    private static Character attacker(int str, int dex) {
        Character c = new Character(1L);
        c.setStrStat((short) str);
        c.setDexStat((short) dex);
        return c;
    }

    private static MobData snail(int maxHp, int pdd) {
        MobData d = new MobData(100_100);
        d.setMaxHp(maxHp);
        d.setPdd(pdd);
        return d;
    }

    @Test
    @DisplayName("物理攻击扣血并保留存活状态")
    void physicalAttackDealsDamage() {
        Character chr = attacker(50, 10);
        MapleMonster snail = new MapleMonster(snail(100, 0));

        CombatSystem.DamageResult result = combat.physicalAttack(chr, snail, CombatSystem.BARE_HAND_WATK);

        assertThat(result.damage()).isPositive();
        assertThat(snail.getHp()).isEqualTo(100 - result.damage());
        assertThat(result.targetAlive()).isTrue();
    }

    @Test
    @DisplayName("多次攻击致死：alive=false、HP 不为负")
    void attackUntilDeath() {
        Character chr = attacker(500, 10);
        MapleMonster snail = new MapleMonster(snail(10, 0));

        combat.physicalAttack(chr, snail, 10);
        combat.physicalAttack(chr, snail, 10);
        CombatSystem.DamageResult last = combat.physicalAttack(chr, snail, 10);

        assertThat(last.targetAlive()).isFalse();
        assertThat(snail.isAlive()).isFalse();
        assertThat(snail.getHp()).isZero();
    }

    @Test
    @DisplayName("防御削减伤害（pdd>0 时伤害更低）")
    void defenseReducesDamage() {
        Character chr = attacker(500, 10);
        MapleMonster soft = new MapleMonster(snail(1000, 0));
        MapleMonster armored = new MapleMonster(snail(1000, 100));

        int softDmg = combat.physicalAttack(chr, soft, 10).damage();
        int armorDmg = combat.physicalAttack(chr, armored, 10).damage();
        assertThat(armorDmg).isLessThan(softDmg);
        assertThat(armorDmg).isPositive();   // 下限 1
    }

    @Test
    @DisplayName("版本门拒绝换代后的迟到攻击")
    void versionGateBlocksStaleAttack() {
        Character chr = new Character(versionGate.currentVersion());
        chr.setStrStat((short) 50);
        chr.setDexStat((short) 10);
        MapleMonster snail = new MapleMonster(snail(100, 0));

        versionGate.onReload();   // 逻辑换代
        CombatSystem.DamageResult result = combat.physicalAttack(chr, snail, 1);

        assertThat(result.damage()).isZero();
        assertThat(snail.getHp()).isEqualTo(100);   // 未扣
    }
}
