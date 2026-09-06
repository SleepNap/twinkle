package org.gms.domain.game.item;

/** Character.wz 的装备穿戴条件投影；不包含装备实例的强化属性。 */
public record EquipmentData(String slot, boolean cash, int requiredJob,
                            int requiredStr, int requiredDex, int requiredInt, int requiredLuk,
                            int requiredFame, int gender, boolean bindOnEquip, boolean uniqueEquipped,
                            int upgradeSlots) {
}
