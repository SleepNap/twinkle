package org.gms.channel;

import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;

/**
 * NPC 对话包构造（v83 字节级兼容红线 1，布局思路参考自 BeiDou-Server 的
 * PacketCreator.getNPCTalk，实现自研）。
 *
 * <p>通用头：{@code byte 4(说话者=NPC) + int npcId + byte msgType + byte speaker(0) + string 文本 + [msgType 附加]}。
 * msgType：0=普通（结束字节 00 00=OK / 00 01=Next / 01 00=Prev / 01 01=Next+Prev）、
 * 1=是/否、4=选项列表、2=文本输入、3=数字输入、7=造型选择。
 */
public final class NpcPacketFactory {

    private NpcPacketFactory() {
    }

    /** 普通对话（msgType 0 + 结束字节：1=OK、2=Next、3=Prev、4=Next+Prev）。 */
    public static OutPacket talk(int npcId, String text, int endType) {
        ByteArrayOutPacket p = base(npcId, 0);
        p.writeString(text);
        switch (endType) {
            case 1 -> p.writeByte(0).writeByte(0);       // OK
            case 2 -> p.writeByte(0).writeByte(1);       // Next
            case 3 -> p.writeByte(1).writeByte(0);       // Prev
            default -> p.writeByte(1).writeByte(1);      // Next+Prev
        }
        return p;
    }

    /** 是/否（msgType 1）。 */
    public static OutPacket yesNo(int npcId, String text) {
        ByteArrayOutPacket p = base(npcId, 1);
        p.writeString(text);
        return p;
    }

    /** 接受/拒绝（msgType 0x0C）。 */
    public static OutPacket acceptDecline(int npcId, String text) {
        ByteArrayOutPacket p = base(npcId, 0x0C);
        p.writeString(text);
        return p;
    }

    /** 选项列表（msgType 4）：byte 选项数 + 每项 int（下标即选择值）。 */
    public static OutPacket simple(int npcId, String text, int optionCount) {
        ByteArrayOutPacket p = base(npcId, 4);
        p.writeString(text);
        p.writeByte(optionCount);
        for (int i = 0; i < optionCount; i++) {
            p.writeInt(i);
        }
        return p;
    }

    /** 文本输入（msgType 2）：默认文本 + int 0。 */
    public static OutPacket getText(int npcId, String text, String defaultText) {
        ByteArrayOutPacket p = base(npcId, 2);
        p.writeString(text);
        p.writeString(defaultText);
        p.writeInt(0);
        return p;
    }

    /** 数字输入（msgType 3）：默认值 + min + max + 0。 */
    public static OutPacket getNumber(int npcId, String text, int def, int min, int max) {
        ByteArrayOutPacket p = base(npcId, 3);
        p.writeString(text);
        p.writeInt(def);
        p.writeInt(min);
        p.writeInt(max);
        p.writeInt(0);
        return p;
    }

    /** 造型选择（msgType 7）：byte 数量 + 每项 int style。 */
    public static OutPacket style(int npcId, String text, int[] styles) {
        ByteArrayOutPacket p = base(npcId, 7);
        p.writeString(text);
        p.writeByte(styles.length);
        for (int s : styles) {
            p.writeInt(s);
        }
        return p;
    }

    private static ByteArrayOutPacket base(int npcId, int msgType) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.NPC_TALK.getValue());
        p.writeByte(4);                 // nSpeakerTypeID = NPC
        p.writeInt(npcId);
        p.writeByte(msgType);
        p.writeByte(0);                 // speaker：NPC 在左
        return p;
    }
}
