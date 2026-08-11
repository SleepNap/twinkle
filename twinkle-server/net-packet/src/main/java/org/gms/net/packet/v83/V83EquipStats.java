package org.gms.net.packet.v83;

/** v83 装备物品的可变属性协议投影。 */
public record V83EquipStats(
        int upgradeSlots,
        int level,
        int strength,
        int dexterity,
        int intelligence,
        int luck,
        int hp,
        int mp,
        int weaponAttack,
        int magicAttack,
        int weaponDefense,
        int magicDefense,
        int accuracy,
        int avoidability,
        int hands,
        int speed,
        int jump,
        int vicious,
        int itemLevel,
        long itemExp) {

    public static V83EquipStats empty() {
        return new V83EquipStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
