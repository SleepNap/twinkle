package org.gms.channel;

import org.gms.domain.game.PlayerCharacter;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.map.MapNpc;
import org.gms.domain.game.spi.EquipmentState.SlotMove;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.v83.V83ChannelId;
import org.gms.net.packet.v83.V83FileTime;
import org.gms.net.packet.v83.V83ItemPacketWriter;
import org.gms.net.packet.v83.V83ItemSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** v83 游戏操作响应；协议字段事实参考北斗，编码及状态差异计算为独立实现。 */
public final class GameplayPackets {
    public static final int STR = 0x40, DEX = 0x80, INT = 0x100, LUK = 0x200;
    public static final int HP = 0x400, MAX_HP = 0x800, MP = 0x1000, MAX_MP = 0x2000;
    public static final int AP = 0x4000, SP = 0x8000, EXP = 0x10000, MESO = 0x40000;

    private GameplayPackets() { }

    public static ByteArrayOutPacket packet(SendOpcode opcode) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeShort(opcode.getValue());
        return packet;
    }

    public static OutPacket enableActions() { return stats(Map.of()); }

    public static OutPacket stats(Map<Integer, Integer> values) {
        ByteArrayOutPacket packet = packet(SendOpcode.STAT_CHANGED);
        packet.writeBool(true);
        packet.writeInt(values.keySet().stream().reduce(0, (a, b) -> a | b));
        new TreeMap<>(values).forEach((mask, value) -> {
            if (mask == MESO || mask == EXP) packet.writeInt(value);
            else packet.writeShort(value);
        });
        return packet;
    }

    public static OutPacket warp(PlayerCharacter character, int channelId) {
        ByteArrayOutPacket packet = packet(SendOpcode.SET_FIELD);
        packet.writeInt(V83ChannelId.toWire(channelId));
        packet.writeInt(0);
        packet.writeByte(0);
        packet.writeInt(character.getMap());
        packet.writeByte(character.getSpawnPoint());
        packet.writeShort(character.getHp());
        packet.writeBool(false);
        packet.writeLong(V83FileTime.encode(System.currentTimeMillis()));
        return packet;
    }

    public static OutPacket npc(MapNpc npc) {
        ByteArrayOutPacket packet = packet(SendOpcode.SPAWN_NPC);
        packet.writeInt(npc.objectId());
        packet.writeInt(npc.templateId());
        packet.writeShort(npc.x());
        packet.writeShort(npc.y());
        packet.writeBool(npc.facingLeft());
        packet.writeShort(npc.foothold());
        packet.writeShort(npc.left());
        packet.writeShort(npc.right());
        packet.writeByte(1);
        return packet;
    }

    public static Map<Short, V83ItemSnapshot> inventory(PlayerCharacter character, InventoryType type) {
        Map<Short, V83ItemSnapshot> result = new TreeMap<>();
        for (Item item : character.getInventory(type).items()) {
            result.put(item.getPosition(), ChannelItemProtocolMapper.toSnapshot(item));
        }
        return result;
    }

    /** v83 换装使用移动操作及末尾重算标志；字段布局核对北斗 PacketCreator.modifyInventory。 */
    public static OutPacket equipmentMoves(List<SlotMove> moves, Map<Short, V83ItemSnapshot> boundItems) {
        OutPacket packet = packet(SendOpcode.INVENTORY_OPERATION);
        packet.writeBool(true).writeByte(moves.size() + 2 * boundItems.size());
        for (SlotMove move : moves) {
            packet.writeByte(2).writeByte(1).writeShort(move.source()).writeShort(move.target());
        }
        new TreeMap<>(boundItems).forEach((slot, item) -> {
            packet.writeByte(3).writeByte(1).writeShort(slot);
            packet.writeByte(0).writeByte(1).writeShort(slot);
            V83ItemPacketWriter.write(packet, item, false);
        });
        packet.writeByte(!boundItems.isEmpty() ? 2 : moves.getLast().source() < 0 ? 1 : 2);
        return packet;
    }

    /** 按完整实例投影计算增删，避免仅以 itemId 比较丢失装备、宠物及期限属性。 */
    public static List<OutPacket> inventoryChanges(InventoryType type,
                                                  Map<Short, V83ItemSnapshot> before,
                                                  Map<Short, V83ItemSnapshot> after) {
        List<ByteArrayOutPacket> changes = new ArrayList<>();
        List<Boolean> equippedRemovals = new ArrayList<>();
        before.forEach((slot, item) -> {
            if (!item.equals(after.get(slot))) {
                ByteArrayOutPacket change = new ByteArrayOutPacket();
                change.writeByte(3);
                change.writeByte(type.getType());
                change.writeShort(slot);
                changes.add(change);
                equippedRemovals.add(slot < 0);
            }
        });
        after.forEach((slot, item) -> {
            if (!item.equals(before.get(slot))) {
                ByteArrayOutPacket change = new ByteArrayOutPacket();
                change.writeByte(0);
                change.writeByte(type.getType());
                change.writeShort(slot);
                V83ItemPacketWriter.write(change, item, false);
                changes.add(change);
                equippedRemovals.add(false);
            }
        });
        List<OutPacket> packets = new ArrayList<>();
        for (int start = 0; start < changes.size(); start += 255) {
            List<ByteArrayOutPacket> batch = changes.subList(start, Math.min(start + 255, changes.size()));
            ByteArrayOutPacket packet = packet(SendOpcode.INVENTORY_OPERATION);
            packet.writeBool(true);
            packet.writeByte(batch.size());
            batch.forEach(change -> packet.writeBytes(change.getBytes()));
            if (equippedRemovals.subList(start, start + batch.size()).contains(true)) packet.writeByte(2);
            packets.add(packet);
        }
        return packets;
    }
}
