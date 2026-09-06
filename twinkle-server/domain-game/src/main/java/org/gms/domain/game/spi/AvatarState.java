package org.gms.domain.game.spi;

/** 角色地图姿态与椅子的稳定接口；可替换逻辑不引用具体角色或背包类。 */
public interface AvatarState extends CharacterState {
    public int getStance();
    public void setStance(int stance);
    public int getFoothold();
    public void setFoothold(int foothold);
    public int getChairItemId();
    public void setChairItemId(int itemId);
    public boolean ownsUsableItem(int itemId, byte inventoryType, long now);
}
