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
        V83SkillPoints.write(packet, stats.job(), stats.sp());
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
     * <p>普通武器属于可见装备列表，现金武器使用独立字段；现金服饰覆盖原部位，原装备进入遮盖列表。
     * 字段含义核对自 BeiDou-Server addCharEquips；按两组槽位投影独立实现。宠物槽待接入。
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
        Map<Integer, Integer> cosmetics = new TreeMap<>();
        Map<Integer, Integer> masked = new TreeMap<>();
        int cashWeapon = 0;
        for (V83EquippedItem item : look.equippedItems()) {
            if (item.position() >= 0) continue;
            int slot = -item.position();
            if (slot == 111) cashWeapon = item.itemId();
            else if (slot > 0 && slot < 100) visible.put(slot, item.itemId());
            else if (slot > 100 && slot < 200) cosmetics.put(slot - 100, item.itemId());
        }
        cosmetics.forEach((slot, item) -> {
            Integer original = visible.put(slot, item);
            if (original != null) masked.put(slot, original);
        });
        for (Map.Entry<Integer, Integer> entry : visible.entrySet()) {
            packet.writeByte(entry.getKey());
            packet.writeInt(entry.getValue());
        }
        packet.writeByte(0xFF);          // 普通装备结束
        masked.forEach((slot, item) -> { packet.writeByte(slot); packet.writeInt(item); });
        packet.writeByte(0xFF);          // 被现金外观遮盖的装备结束
        packet.writeInt(cashWeapon);
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
