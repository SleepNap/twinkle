package org.gms.channel;

import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.v83.V83ItemPacketWriter;
import org.gms.net.packet.v83.V83ItemSnapshot;

import java.util.List;

/**
 * v83 账号仓库（storage / trunk）发包构造。
 *
 * <p>仓库包与背包等其他业务共用 {@link SendOpcode#STORAGE}，包体首字节是仓库模式：
 * 0x16 开窗、0x0D 存入、0x09 取出、0x0F 整理、0x13 金币搬运；错误包没有模式字节，
 * 首字节直接是错误码。
 *
 * <p>所有含物品的包都不写位置字段（{@link V83ItemPacketWriter#write} 的
 * {@code includePosition=false}）：客户端按「该类型内的顺序下标」定位仓库物品，
 * 写位置会让客户端多读一字节而整段错位。物品顺序由服务层保证（按类型内下标升序）。
 *
 * <p>字段顺序对照北斗 PacketCreator 的 getStorage / getStorageError / mesoStorage /
 * storeStorage / takeOutStorage / arrangeStorage，字节级不可调整。
 */
public final class StoragePackets {
    /** 0x0A：背包已满，取不出。 */
    public static final int ERROR_BAG_FULL = 0x0A;
    /** 0x0B：金币不足（存入/取出费用或搬运金额不合法）。 */
    public static final int ERROR_NO_MESO = 0x0B;
    /** 0x0C：唯一性道具冲突（同时只能持有一件）。 */
    public static final int ERROR_ONE_OF_A_KIND = 0x0C;
    /** 0x11：仓库已满，存不进。 */
    public static final int ERROR_STORAGE_FULL = 0x11;

    /** 仓库模式字节值。 */
    private static final int MODE_OPEN = 0x16;
    private static final int MODE_STORE = 0x0D;
    private static final int MODE_TAKE_OUT = 0x09;
    private static final int MODE_ARRANGE = 0x0F;
    private static final int MODE_MESO = 0x13;

    /** 开窗包的固定短整型常量（参考实现写 0x7E，客户端按此识别开窗上下文）。 */
    private static final int OPEN_FLAG = 0x7E;
    /** 整理包的固定单字节常量：参考实现写的是「一个值为 124 的字节」，不是 124 字节填充。 */
    private static final int ARRANGE_FLAG = 124;
    /** 整理包保留区长度：参考实现 skip(10)，即 10 个 0 字节。 */
    private static final int ARRANGE_RESERVED = 10;
    /** 金币搬运包的固定常量（参考实现 writeShort(2)）。 */
    private static final int MESO_FLAG = 2;
    /** 背包类型字节合法区间：1=装备、2=消耗、3=布置、4=其他、5=现金。 */
    private static final int MIN_TYPE = 1;
    private static final int MAX_TYPE = 5;

    private StoragePackets() { }

    /**
     * 开窗：回显 NPC、可用格数、仓库金币和全部库存物品。
     *
     * @param npcId 触发开箱的 NPC 模板 id
     * @param slots 仓库总格数（1~48）
     * @param meso  仓库内金币
     * @param items 全部库存物品，按 (类型, 类型内下标) 升序
     */
    public static OutPacket open(int npcId, int slots, int meso, List<V83ItemSnapshot> items) {
        ByteArrayOutPacket packet = packet(MODE_OPEN);
        packet.writeInt(npcId);
        packet.writeByte(slots);
        packet.writeShort(OPEN_FLAG);
        packet.writeShort(0);
        packet.writeInt(0);
        packet.writeInt(meso);
        packet.writeShort(0);
        packet.writeByte(count(items));
        writeItems(packet, items);
        packet.writeShort(0);
        packet.writeByte(0);
        return packet;
    }

    /**
     * 存入回执：回传该类型背包在仓库内的全部物品。
     *
     * @param slots       仓库总格数
     * @param typeBitfield 类型位域（{@code 2 << type}），由调用方给出
     * @param changed     该类型在仓库中的全部物品（本次变化后的完整列表）
     * @param changedType 该类型字节值（1-5）；协议不单独写该字节，只用于确认位域
     */
    public static OutPacket store(int slots, int typeBitfield, List<V83ItemSnapshot> changed, int changedType) {
        return changed(MODE_STORE, slots, typeBitfield, changed, changedType);
    }

    /**
     * 取出回执：回传该类型背包在仓库内的剩余物品。
     *
     * @param slots        仓库总格数
     * @param typeBitfield 类型位域（{@code 2 << type}）
     * @param changed      该类型在仓库中的全部剩余物品
     * @param changedType  该类型字节值（1-5）；协议不单独写该字节，只用于确认位域
     */
    public static OutPacket takeOut(int slots, int typeBitfield, List<V83ItemSnapshot> changed, int changedType) {
        return changed(MODE_TAKE_OUT, slots, typeBitfield, changed, changedType);
    }

    /**
     * 整理回执：回传整理后的全部库存物品。
     *
     * @param slots 仓库总格数
     * @param items 全部库存物品，按 (类型, 类型内下标) 升序
     */
    public static OutPacket arrange(int slots, List<V83ItemSnapshot> items) {
        ByteArrayOutPacket packet = packet(MODE_ARRANGE);
        packet.writeByte(slots);
        packet.writeByte(ARRANGE_FLAG);
        packet.skip(ARRANGE_RESERVED);
        packet.writeByte(count(items));
        writeItems(packet, items);
        packet.writeByte(0);
        return packet;
    }

    /**
     * 金币搬运回执：只回仓库内金币的当前值，角色金币走 STAT_CHANGED。
     *
     * @param slots 仓库总格数
     * @param meso  仓库内金币（搬运后的值）
     */
    public static OutPacket meso(int slots, int meso) {
        ByteArrayOutPacket packet = packet(MODE_MESO);
        packet.writeByte(slots);
        packet.writeShort(MESO_FLAG);
        packet.writeShort(0);
        packet.writeInt(0);
        packet.writeInt(meso);
        return packet;
    }

    /**
     * 错误回包：首字节即错误码，无模式字节、无附加字段。
     *
     * @param code 见 {@link #ERROR_BAG_FULL} 等常量
     */
    public static OutPacket error(int code) {
        return packet(code);
    }

    /** 存入/取出共用布局：类型只出现在位域里，不另写类型字节。 */
    private static OutPacket changed(int mode, int slots, int typeBitfield, List<V83ItemSnapshot> changed,
                                     int changedType) {
        ByteArrayOutPacket packet = packet(mode);
        packet.writeByte(slots);
        packet.writeShort(bitfieldOf(typeBitfield, changedType));
        packet.writeShort(0);
        packet.writeInt(0);
        packet.writeByte(count(changed));
        writeItems(packet, changed);
        return packet;
    }

    /** 类型位域：合法类型时以类型字节为准（{@code 2 << type}），否则用调用方给出的位域。 */
    private static int bitfieldOf(int typeBitfield, int changedType) {
        return changedType >= MIN_TYPE && changedType <= MAX_TYPE ? 2 << changedType : typeBitfield;
    }

    private static void writeItems(ByteArrayOutPacket packet, List<V83ItemSnapshot> items) {
        if (items == null) {
            return;
        }
        for (V83ItemSnapshot item : items) {
            // 仓库物品不写位置：客户端按下标而非背包槽位识别。
            V83ItemPacketWriter.write(packet, item, false);
        }
    }

    private static int count(List<V83ItemSnapshot> items) {
        return items == null ? 0 : items.size();
    }

    /** 写 STORAGE opcode + 模式字节。 */
    private static ByteArrayOutPacket packet(int mode) {
        ByteArrayOutPacket packet = GameplayPackets.packet(SendOpcode.STORAGE);
        packet.writeByte(mode);
        return packet;
    }
}
