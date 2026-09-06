package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.v83.V83CharacterLook;
import org.gms.net.packet.v83.V83CharacterPacketWriter;
import org.gms.net.packet.v83.V83EquippedItem;

import java.util.Map;
import java.util.TreeMap;

/**
 * 同屏角色协议。字段核对来源：BeiDou-Server PacketCreator 的玩家生成、表情、椅子及远程增益报文。
 * 使用本项目状态投影独立编码；尚未接入的宠物、公会、婚戒和摊位写合法空段。
 */
public final class PlayerPresencePackets {
    private PlayerPresencePackets() { }

    public static OutPacket spawn(PlayerCharacter character) {
        synchronized (character) {
            OutPacket packet = GameplayPackets.packet(SendOpcode.SPAWN_PLAYER);
            packet.writeInt((int) character.getId()).writeByte(character.getLevel()).writeString(character.getName());
            packet.writeString("").skip(6); // 公会名和徽章
            // 128 字节远程临时状态：固定二段状态标志，能量/冲刺/骑乘等均未启用。
            // 普通自身 Buff 通过后续 GIVE_FOREIGN_BUFF 同步，避免把它们写进特殊状态段。
            packet.writeLong(0x01FC000000000000L).skip(120);
            packet.writeShort(character.getJob());
            V83CharacterPacketWriter.writeLook(packet, look(character), false);
            packet.writeInt(0).writeInt(0).writeInt(character.getChairItemId());
            packet.writeShort(character.getX()).writeShort(character.getY());
            packet.writeByte(character.getStance()).writeShort(character.getFoothold());
            packet.writeByte(0).writeByte(0); // 无宠物
            packet.writeInt(1).writeLong(0); // 未骑乘
            packet.skip(9); // 摊位、黑板、三类戒指、贺卡、两保留位和队伍颜色
            return packet;
        }
    }

    public static V83CharacterLook look(PlayerCharacter character) {
        var equipped = character.getInventory(InventoryType.EQUIP).items().stream()
                .filter(item -> item.getPosition() < 0)
                .map(item -> new V83EquippedItem(item.getPosition(), item.getId())).toList();
        return new V83CharacterLook(character.getGender(), character.getSkinColor(), character.getFace(),
                character.getHair(), equipped);
    }

    public static OutPacket remove(long id) {
        return GameplayPackets.packet(SendOpcode.REMOVE_PLAYER_FROM_MAP).writeInt((int) id);
    }

    public static OutPacket expression(long id, int expression) {
        return GameplayPackets.packet(SendOpcode.FACIAL_EXPRESSION).writeInt((int) id).writeInt(expression);
    }

    public static OutPacket chair(long id, int itemId) {
        return GameplayPackets.packet(SendOpcode.SHOW_CHAIR).writeInt((int) id).writeInt(itemId);
    }

    public static OutPacket cancelChair() {
        return GameplayPackets.packet(SendOpcode.CANCEL_CHAIR).writeByte(0);
    }

    public static OutPacket buff(long id, Map<Long, Integer> values) {
        OutPacket packet = GameplayPackets.packet(SendOpcode.GIVE_FOREIGN_BUFF).writeInt((int) id);
        packet.writeLong(0).writeLong(values.keySet().stream().reduce(0L, (mask, flag) -> mask | flag));
        new TreeMap<>(values).values().forEach(packet::writeShort);
        return packet.writeInt(0).writeShort(0);
    }

    public static OutPacket cancelBuff(long id, long mask) {
        return GameplayPackets.packet(SendOpcode.CANCEL_FOREIGN_BUFF).writeInt((int) id).writeLong(0).writeLong(mask);
    }

    public static OutPacket skillEffect(long id, int skill, int level, int direction) {
        return GameplayPackets.packet(SendOpcode.SHOW_FOREIGN_EFFECT).writeInt((int) id)
                .writeByte(1).writeInt(skill).writeByte(0).writeByte(level).writeByte(direction);
    }
}
