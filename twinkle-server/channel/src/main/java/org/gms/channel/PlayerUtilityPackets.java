package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.control.ControlSettings;
import org.gms.domain.game.control.ControlSettings.Binding;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;

/** 角色详情和操作设置报文；字段顺序核对 BeiDou-Server PacketCreator，编码独立实现。 */
public final class PlayerUtilityPackets {
    private PlayerUtilityPackets() { }

    public static OutPacket keymap(ControlSettings settings) {
        OutPacket packet = GameplayPackets.packet(SendOpcode.KEYMAP).writeByte(0);
        for (int key = 0; key < 90; key++) {
            Binding binding = settings.bindings().getOrDefault(key, new Binding(0, 0));
            packet.writeByte(binding.type()).writeInt(binding.action());
        }
        return packet;
    }

    public static OutPacket macros(ControlSettings settings) {
        OutPacket packet = GameplayPackets.packet(SendOpcode.MACRO_SYS_DATA_INIT).writeByte(settings.macros().size());
        settings.macros().forEach(macro -> {
            packet.writeString(macro.name()).writeBool(macro.shout());
            macro.skills().forEach(packet::writeInt);
        });
        return packet;
    }

    public static OutPacket quickSlots(ControlSettings settings) {
        OutPacket packet = GameplayPackets.packet(SendOpcode.QUICKSLOT_INIT).writeBool(!settings.quickSlots().isEmpty());
        settings.quickSlots().forEach(packet::writeInt);
        return packet;
    }

    public static OutPacket characterInfo(PlayerCharacter character) {
        synchronized (character) {
            OutPacket packet = GameplayPackets.packet(SendOpcode.CHAR_INFO);
            packet.writeInt((int) character.getId()).writeByte(character.getLevel())
                    .writeShort(character.getJob()).writeShort(character.getFame());
            packet.writeByte(0).writeString("").writeString(""); // 婚姻、公会和联盟资料待对应系统接入
            packet.skip(4); // 勋章标记、宠物列表结束、骑宠、愿望清单数量
            packet.skip(20); // 图鉴统计与封面怪物，不能把封面道具 ID 当作怪物 ID
            var medal = character.getInventory(InventoryType.EQUIP).getItem((short) -49);
            packet.writeInt(medal == null ? 0 : medal.getId());
            var completed = character.quests().values().stream()
                    .filter(quest -> quest.getState() == QuestStatus.State.COMPLETED)
                    .map(QuestStatus::getQuestId).filter(id -> id >= 29000 && id < 30000).sorted().toList();
            packet.writeShort(completed.size()); completed.forEach(packet::writeShort);
            return packet;
        }
    }

    public static OutPacket partyChat(String name, String text) {
        return GameplayPackets.packet(SendOpcode.MULTICHAT).writeByte(1).writeString(name).writeString(text);
    }
}
