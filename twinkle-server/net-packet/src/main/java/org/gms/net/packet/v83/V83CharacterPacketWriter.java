package org.gms.net.packet.v83;

import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;

import java.util.Map;
import java.util.TreeMap;

/**
 * v83 角色公共字节段写入器。
 *
 * <p>本类是 twinkle 根据 v83 客户端字节布局和本项目 golden 测试独立实现的协议基础，
 * 不包含登录、频道或数据库业务逻辑。所有调用方共享同一实现，避免相同角色字段在不同
 * PacketFactory 中逐渐产生字节偏移。
 */
public final class V83CharacterPacketWriter {

    private static final int CHARACTER_NAME_BYTES = 13;

    private V83CharacterPacketWriter() {
    }

    /** 写入 v83 addCharStats 公共段。 */
    public static void writeStats(OutPacket packet, V83CharacterStats stats) {
        packet.writeInt(stats.id());
        writeFixedString(packet, stats.name(), CHARACTER_NAME_BYTES);
        packet.writeByte(stats.gender());
        packet.writeByte(stats.skinColor());
        packet.writeInt(stats.face());
        packet.writeInt(stats.hair());
        packet.writeLong(0);             // 宠物 x3；宠物槽状态尚未进入协议投影
        packet.writeLong(0);
        packet.writeLong(0);
        packet.writeByte(stats.level());
        packet.writeShort(stats.job());
        packet.writeShort(stats.strength());
        packet.writeShort(stats.dexterity());
        packet.writeShort(stats.intelligence());
        packet.writeShort(stats.luck());
        packet.writeShort(stats.hp());
        packet.writeShort(stats.maxHp());
        packet.writeShort(stats.mp());
        packet.writeShort(stats.maxMp());
        packet.writeShort(stats.ap());
        packet.writeShort(firstRemainingSp(stats.sp()));
        packet.writeInt((int) stats.exp());
        packet.writeShort(stats.fame());
        packet.writeInt((int) stats.gachaExp());
        packet.writeInt(stats.mapId());
        packet.writeByte(stats.spawnPoint());
        packet.writeInt(0);
    }

    /**
     * 写入 v83 addCharLook 公共段。
     *
     * <p>当前协议投影覆盖普通已穿戴装备；现金外观覆盖与宠物槽在获得真实录包后扩展。
     */
    public static void writeLook(OutPacket packet, V83CharacterLook look, boolean mega) {
        packet.writeByte(look.gender());
        packet.writeByte(look.skinColor());
        packet.writeInt(look.face());
        packet.writeBool(!mega);
        packet.writeInt(look.hair());
        writeEquippedItems(packet, look);
    }

    private static void writeEquippedItems(OutPacket packet, V83CharacterLook look) {
        Map<Integer, Integer> visible = new TreeMap<>();
        int weapon = 0;
        for (V83EquippedItem item : look.equippedItems()) {
            int slot = Math.abs(item.position());
            if (slot == 0) {
                continue;
            }
            if (slot == 11) {
                weapon = item.itemId();
                continue;
            }
            visible.put(slot, item.itemId());
        }
        for (Map.Entry<Integer, Integer> entry : visible.entrySet()) {
            packet.writeByte(entry.getKey());
            packet.writeInt(entry.getValue());
        }
        packet.writeByte(0xFF);          // 普通装备结束
        packet.writeByte(0xFF);          // masked 装备结束（现金覆盖后续扩展）
        packet.writeInt(weapon);
        packet.writeInt(0);              // 宠物 x3
        packet.writeInt(0);
        packet.writeInt(0);
    }

    private static short firstRemainingSp(String sp) {
        if (sp == null || sp.isBlank()) {
            return 0;
        }
        int comma = sp.indexOf(',');
        String first = comma > 0 ? sp.substring(0, comma) : sp;
        try {
            return Short.parseShort(first.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeFixedString(OutPacket packet, String value, int fixedLength) {
        byte[] source = value.getBytes(InPacket.DEFAULT_CHARSET);
        byte[] target = new byte[fixedLength];
        System.arraycopy(source, 0, target, 0, Math.min(source.length, fixedLength));
        packet.writeBytes(target);
    }
}
