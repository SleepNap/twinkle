package org.gms.domain.game.mob;

import lombok.Getter;
import lombok.Setter;

/**
 * 怪物运行时对象（稳定层，纯数据，内存态权威）。静态属性来自 {@link MobData}（WZ 加载），
 * 运行时状态（HP/位置/存活）在此维护。刷怪/战斗逻辑在可替换层系统，本类只做数据结构。
 * 手动 new、不进容器（红线 4）。
 */
@Getter
@Setter
public class MapleMonster {

    private final MobData data;
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

    /** 扣血；hp 归零标记死亡（最小值 0）。 */
    public void takeDamage(int damage) {
        if (damage <= 0 || !alive) {
            return;
        }
        hp = Math.max(0, hp - damage);
        if (hp == 0) {
            alive = false;
        }
    }

    public boolean isAlive() {
        return alive;
    }
}
