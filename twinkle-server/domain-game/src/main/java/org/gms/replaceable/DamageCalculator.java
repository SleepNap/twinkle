package org.gms.replaceable;

/**
 * 伤害计算（可替换层，纯函数、无状态）。
 *
 * <p>v83 物理基础伤害公式，思路参考自 BeiDou-Server 的
 * {@code Character.calculateMaxBaseDamage}（非逐字复制，参数化自研）：
 * {@code maxBase = ceil((weaponMult × 主属性 + 副属性) / 100 × watk)}。
 *
 * <p>武器类型系数（weaponMult）由武器类型决定（如单手剑约 1.2），M2 装备系统
 * 落地后由装备推导；当前战斗调用方传 1.0（徒手/未装备）。
 */
public final class DamageCalculator {

    private DamageCalculator() {
    }

    /**
     * 物理最大基础伤害。
     *
     * @param mainStat      主属性（近战 str、弓 dex、贼 luk）
     * @param secondaryStat 副属性（近战 dex、弓 str、贼 dex+str）
     * @param watk          武器攻击力（含装备加成）
     * @param weaponMult    武器类型系数（无武器 1.0）
     */
    public static int maxPhysical(int mainStat, int secondaryStat, int watk, double weaponMult) {
        if (watk < 0 || mainStat < 0 || secondaryStat < 0) {
            return 0;
        }
        return (int) Math.ceil(((weaponMult * mainStat + secondaryStat) / 100.0) * watk);
    }

    /**
     * 物理实际伤害（基础伤害扣目标物理防御）。
     *
     * @param pdd 目标物理防御（PDDamage）
     */
    public static int physicalDamage(int mainStat, int secondaryStat, int watk, double weaponMult, int pdd) {
        int max = maxPhysical(mainStat, secondaryStat, watk, weaponMult);
        int reduced = max - (int) (pdd * 0.5);
        return Math.max(1, reduced);
    }
}
