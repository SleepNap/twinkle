package org.gms.domain.game.mob;

import org.gms.domain.game.spi.MonsterState;
import lombok.Getter;
import lombok.Setter;

/**
 * 怪物运行时对象（稳定层，纯数据，内存态权威）。静态属性来自 {@link MobData}（WZ 加载），
 * 运行时状态（HP/位置/存活）在此维护。刷怪/战斗逻辑在可替换层系统，本类只做数据结构。
 * 手动 new、不进容器（红线 4）。
 */
@Getter
@Setter
public class MapleMonster implements MonsterState {

    private volatile MobData data;
    /** 地图内对象 id（v83 oid，刷怪时由地图分配，客户端据此寻址）。 */
    private int objectId;
    private int hp;
    private int mp;
    private int x;
    private int y;
    private boolean alive = true;

    public MapleMonster(MobData data) {
        this.data = data;
        this.hp = data.getMaxHp();
        this.mp = data.getMaxMp();
    }

    /**
     * 替换怪物的 WZ 静态属性，保留已发生的战斗状态；新上限降低时收紧当前 HP/MP。
     */
    public synchronized void replaceWzData(MobData replacement) {
        this.data = replacement;
        this.hp = Math.min(hp, replacement.getMaxHp());
        this.mp = Math.min(mp, replacement.getMaxMp());
    }

    /** 扣血；hp 归零标记死亡（最小值 0）。 */
    public void takeDamage(int damage) {
        applyDamage(damage);
    }

    /** 一次受击的扣血与死亡认领不可分割；只有首次从存活转为死亡的操作取得结算权。 */
    public synchronized DamageOutcome applyDamage(int damage) {
        if (damage <= 0 || !alive) {
            return new DamageOutcome(0, alive, false);
        }
        int applied = Math.min(hp, damage);
        hp = Math.max(0, hp - damage);
        if (hp == 0) {
            alive = false;
        }
        return new DamageOutcome(applied, alive, !alive);
    }

    @Override public int physicalDefense() { return data.getPdd(); }

    public boolean isAlive() {
        return alive;
    }
}
