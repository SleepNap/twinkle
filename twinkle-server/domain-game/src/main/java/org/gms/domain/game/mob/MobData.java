package org.gms.domain.game.mob;

import lombok.Getter;
import lombok.Setter;

/**
 * 怪物静态数据（稳定层，纯数据）。Mob.wz 解析填充（M2-3），
 * 刷怪/战斗系统经此查怪物属性。手动 new、不进容器（红线 4）。
 */
@Getter
@Setter
public class MobData implements java.io.Serializable {

    private static final long serialVersionUID = 1L;

    private final int mobId;
    private int level;
    private int maxHp;
    private int maxMp;
    private int exp;
    /** 物理攻击力 / 物理防御 / 魔法攻击力 / 魔法防御（v83 字段 PADamage/PDDamage/MADamage/MDDamage）。 */
    private int pad;
    private int pdd;
    private int mad;
    private int mdd;
    private int acc;
    private int eva;
    private int speed;
    private boolean boss;
    private boolean undead;
    private boolean bodyAttack;
    private int pushed;

    public MobData(int mobId) {
        this.mobId = mobId;
    }
}
