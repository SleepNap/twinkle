package org.gms.net.packet.v83;

import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;

import java.util.Arrays;

/**
 * v83 公共物品字节段写入器。
 *
 * <p>布局由客户端行为、录包目标和本项目 golden 测试固化；调用方只提供协议投影，
 * 不在交易、背包或商店 PacketFactory 中各写一套物品字段。
 */
public final class V83ItemPacketWriter {

    private V83ItemPacketWriter() {
    }

    /**
     * 写入物品信息。
     *
     * @param includePosition 背包/进图段为 true；交易等已单独写槽位的场景为 false
     */
    public static void write(OutPacket packet, V83ItemSnapshot item, boolean includePosition) {
        if (includePosition) {
            writePosition(packet, item);
        }
        packet.writeByte(item.itemType());
        packet.writeInt(item.itemId());
        packet.writeBool(item.cash());
        if (item.cash()) {
            packet.writeLong(item.cashId());
        }
        packet.writeLong(expiration(item.expiration()));
        if (item.petStats() != null) {
            writePet(packet, item);
        } else if (item.itemType() == 1) {
            writeEquip(packet, item);
        } else {
            writeStackItem(packet, item);
        }
    }

    private static void writePet(OutPacket packet, V83ItemSnapshot item) {
        V83PetStats pet = item.petStats();
        byte[] encodedName = pet.name().getBytes(InPacket.DEFAULT_CHARSET);
        packet.writeBytes(Arrays.copyOf(encodedName, 13));
        packet.writeByte(pet.level());
        packet.writeShort(pet.closeness());
        packet.writeByte(pet.fullness());
        packet.writeLong(expiration(item.expiration()));
        packet.writeShort(pet.attribute());
        packet.writeShort(pet.skill());
        packet.writeInt(pet.remainLife());
        packet.writeShort(pet.itemAttribute());
    }

    private static void writePosition(OutPacket packet, V83ItemSnapshot item) {
        int position = item.position();
        if (item.itemType() == 1) {
            int normalized = position < 0 ? -position : position;
            packet.writeShort(normalized > 100 ? normalized - 100 : normalized);
        } else {
            packet.writeByte(position);
        }
    }

    private static void writeStackItem(OutPacket packet, V83ItemSnapshot item) {
        packet.writeShort(item.quantity());
        packet.writeString(item.owner());
        packet.writeShort(item.flag());
        if (isRechargeable(item.itemId())) {
            packet.writeInt(2);
            packet.writeBytes(new byte[]{0x54, 0, 0, 0x34});
        }
    }

    private static void writeEquip(OutPacket packet, V83ItemSnapshot item) {
        V83EquipStats equip = item.equipStats();
        packet.writeByte(equip.upgradeSlots());
        packet.writeByte(equip.level());
        packet.writeShort(equip.strength());
        packet.writeShort(equip.dexterity());
        packet.writeShort(equip.intelligence());
        packet.writeShort(equip.luck());
        packet.writeShort(equip.hp());
        packet.writeShort(equip.mp());
        packet.writeShort(equip.weaponAttack());
        packet.writeShort(equip.magicAttack());
        packet.writeShort(equip.weaponDefense());
        packet.writeShort(equip.magicDefense());
        packet.writeShort(equip.accuracy());
        packet.writeShort(equip.avoidability());
        packet.writeShort(equip.hands());
        packet.writeShort(equip.speed());
        packet.writeShort(equip.jump());
        packet.writeString(item.owner());
        packet.writeShort(item.flag());
        if (item.cash()) {
            for (int i = 0; i < 10; i++) {
                packet.writeByte(0x40);
            }
        } else {
            packet.writeByte(0);         // 装备成长状态标记
            packet.writeByte(equip.itemLevel());
            packet.writeInt((int) equip.itemExp());
            packet.writeInt(equip.vicious());
            packet.writeLong(0);
        }
        packet.writeLong(V83FileTime.encode(-2));
        packet.writeInt(-1);
    }

    private static long expiration(long timestamp) {
        return V83FileTime.encode(timestamp);
    }

    private static boolean isRechargeable(int itemId) {
        int category = itemId / 10000;
        return category == 207 || category == 233;
    }
}
