package org.gms.domain.game.inventory;

import lombok.Getter;
import lombok.Setter;

/**
 * 装备（纯数据，扩展 {@link Item}）。字段集对齐 v83 存档 Equip 结构（红线 3）。
 * Lombok 生成 getter/setter（红线 11）；copy 深拷贝带全扩展字段。
 */
@Getter
@Setter
public class Equip extends Item {

    private byte upgradeSlots;
    private short level;
    private short strStat;
    private short dexStat;
    private short intStat;
    private short lukStat;
    private short hp;
    private short mp;
    private short wAtk;
    private short mAtk;
    private short wDef;
    private short mDef;
    private short acc;
    private short avoid;
    private short hands;
    private short speed;
    private short jump;
    private byte vicious;
    private byte itemLevel;
    private long itemExp;
    private int ringId;

    public Equip(int id) {
        super(id);
    }

    @Override
    public Equip copy() {
        Equip copy = new Equip(getId());
        copyBase(copy);
        copy.upgradeSlots = upgradeSlots;
        copy.level = level;
        copy.strStat = strStat;
        copy.dexStat = dexStat;
        copy.intStat = intStat;
        copy.lukStat = lukStat;
        copy.hp = hp;
        copy.mp = mp;
        copy.wAtk = wAtk;
        copy.mAtk = mAtk;
        copy.wDef = wDef;
        copy.mDef = mDef;
        copy.acc = acc;
        copy.avoid = avoid;
        copy.hands = hands;
        copy.speed = speed;
        copy.jump = jump;
        copy.vicious = vicious;
        copy.itemLevel = itemLevel;
        copy.itemExp = itemExp;
        copy.ringId = ringId;
        return copy;
    }
}
