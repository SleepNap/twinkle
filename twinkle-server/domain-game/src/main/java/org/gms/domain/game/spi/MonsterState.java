package org.gms.domain.game.spi;

/** 怪物战斗状态契约；实际怪物和死亡归属保留在宿主。 */
public interface MonsterState {
    public int physicalDefense();
    public DamageOutcome applyDamage(int damage);
    public record DamageOutcome(int damage, boolean alive, boolean killed) { }
}
