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
    private short str;
    private short dex;
    private short intStat;
    private short luk;
    private short hp;
    private short mp;
    private short watk;
    private short matk;
    private short wdef;
    private short mdef;
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
        copy.str = str;
        copy.dex = dex;
        copy.intStat = intStat;
        copy.luk = luk;
        copy.hp = hp;
        copy.mp = mp;
        copy.watk = watk;
        copy.matk = matk;
        copy.wdef = wdef;
        copy.mdef = mdef;
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
