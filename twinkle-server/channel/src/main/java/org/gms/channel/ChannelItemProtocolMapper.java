package org.gms.channel;

import org.gms.domain.game.inventory.Equip;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.inventory.PetItem;
import org.gms.net.packet.v83.V83EquipStats;
import org.gms.net.packet.v83.V83ItemSnapshot;
import org.gms.net.packet.v83.V83PetStats;

/** domain-game 物品到 v83 协议投影的频道侧适配器。 */
public final class ChannelItemProtocolMapper {

    private ChannelItemProtocolMapper() {
    }

    public static V83ItemSnapshot toSnapshot(Item item) {
        return toSnapshot(item, item.getQuantity());
    }

    public static V83ItemSnapshot toSnapshot(Item item, int quantity) {
        V83EquipStats equipStats = null;
        V83PetStats petStats = null;
        int itemType = 2;
        if (item instanceof Equip equip) {
            itemType = 1;
            equipStats = new V83EquipStats(
                    equip.getUpgradeSlots(), equip.getLevel(), equip.getStr(), equip.getDex(), equip.getIntStat(),
                    equip.getLuk(), equip.getHp(), equip.getMp(), equip.getWatk(), equip.getMatk(), equip.getWdef(),
                    equip.getMdef(), equip.getAcc(), equip.getAvoid(), equip.getHands(), equip.getSpeed(),
                    equip.getJump(), equip.getVicious(), equip.getItemLevel(), equip.getItemExp());
        } else if (item instanceof PetItem pet) {
            petStats = new V83PetStats(
                    pet.getPetName(), pet.getPetLevel(), pet.getCloseness(), pet.getFullness(),
                    pet.getPetAttribute(), pet.getPetSkill(), pet.getRemainLife(), pet.getAttribute());
        }
        long protocolCashId = petStats == null ? item.getCashId() : item.getPetId();
        return new V83ItemSnapshot(
                item.getPosition(), itemType, item.getId(), protocolCashId, item.getExpiration(), quantity,
                item.getOwner(), item.getFlag(), equipStats, petStats);
    }
}
