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

    // 受控写入口：Lombok 的普通 setter 无法校验频道执行归属。
    public void setUpgradeSlots(byte value) { requireStateAccess(); this.upgradeSlots = value; }
    public void setLevel(short value) { requireStateAccess(); this.level = value; }
    public void setStrStat(short value) { requireStateAccess(); this.strStat = value; }
    public void setDexStat(short value) { requireStateAccess(); this.dexStat = value; }
    public void setIntStat(short value) { requireStateAccess(); this.intStat = value; }
    public void setLukStat(short value) { requireStateAccess(); this.lukStat = value; }
    public void setHp(short value) { requireStateAccess(); this.hp = value; }
    public void setMp(short value) { requireStateAccess(); this.mp = value; }
    public void setWAtk(short value) { requireStateAccess(); this.wAtk = value; }
    public void setMAtk(short value) { requireStateAccess(); this.mAtk = value; }
    public void setWDef(short value) { requireStateAccess(); this.wDef = value; }
    public void setMDef(short value) { requireStateAccess(); this.mDef = value; }
    public void setAcc(short value) { requireStateAccess(); this.acc = value; }
    public void setAvoid(short value) { requireStateAccess(); this.avoid = value; }
    public void setHands(short value) { requireStateAccess(); this.hands = value; }
    public void setSpeed(short value) { requireStateAccess(); this.speed = value; }
    public void setJump(short value) { requireStateAccess(); this.jump = value; }
    public void setVicious(byte value) { requireStateAccess(); this.vicious = value; }
    public void setItemLevel(byte value) { requireStateAccess(); this.itemLevel = value; }
    public void setItemExp(long value) { requireStateAccess(); this.itemExp = value; }
    public void setRingId(int value) { requireStateAccess(); this.ringId = value; }
}
