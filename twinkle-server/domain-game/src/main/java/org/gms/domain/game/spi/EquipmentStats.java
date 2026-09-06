package org.gms.domain.game.spi;

/** 当前有效装备的增量属性；与持久化的角色基础属性分开保存。 */
public record EquipmentStats(int str, int dex, int intelligence, int luk, int hp, int mp,
                             int weaponAttack, int magicAttack, int weaponDefense, int magicDefense,
                             int accuracy, int avoid, int hands, int speed, int jump) {
    public static final EquipmentStats EMPTY = new EquipmentStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
