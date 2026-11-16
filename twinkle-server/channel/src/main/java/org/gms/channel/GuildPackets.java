package org.gms.channel;

import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;

import java.util.Arrays;
import java.util.List;

/**
 * v83 公会发包构造（{@code SendOpcode.GUILD_OPERATION 0x41} 的子类型，以及两个独立 opcode）。
 *
 * <p>字节布局按北斗 {@code GuildPackets} 逐字段核对后独立实现：成员名是 13 字节定长字段，
 * 其余都是 int/byte/short。物品与公会无关，这里不涉及实例编码。
 *
 * <p>边界：本期只做最小闭环（创建/邀请/加入/成员列表/退出/公告），GP、等级、徽章变更、
 * 公会 Boss、BBS、联盟都不发送；对应字段按参考实现的空值形态写出（GP=0、allianceId=0、
 * 徽章四字段 0、职级称号用默认值），保证客户端能正常解析整表。
 */
public final class GuildPackets {

    /** 成员名在包里的定长宽度（GBK 字节）。 */
    private static final int NAME_LENGTH = 13;

    /** 公会整表刷新里的成员条目；{@code online} 由频道会话来判定。 */
    public record GuildMemberView(long characterId, String name, int job, int level, int rank, boolean online) { }

    /** 公会整表视图；{@code rankTitles} 固定 5 个（下标 0 对应职级 1）。 */
    public record GuildView(long guildId, String name, List<String> rankTitles, int capacity, int gp,
                            int logoBackground, int logoBackgroundColor, int logoMark, int logoMarkColor,
                            String notice, int allianceId, List<GuildMemberView> members) { }

    private GuildPackets() {
    }

    /** 整表刷新（0x1A）；{@code view} 为 {@code null} 表示已不在公会，包只写一个 0 字节。 */
    public static OutPacket showGuildInfo(GuildView view) {
        ByteArrayOutPacket packet = packet(0x1A);
        if (view == null) {
            packet.writeByte(0);
            return packet;
        }
        packet.writeByte(1);
        packet.writeInt((int) view.guildId());
        packet.writeString(view.name());
        List<String> titles = view.rankTitles();
        for (int i = 0; i < 5; i++) {
            packet.writeString(titles != null && i < titles.size() ? titles.get(i) : "");
        }
        List<GuildMemberView> members = view.members();
        packet.writeByte(members.size());
        for (GuildMemberView member : members) {
            packet.writeInt((int) member.characterId());
        }
        for (GuildMemberView member : members) {
            writeFixedString(packet, member.name(), NAME_LENGTH);
            packet.writeInt(member.job());
            packet.writeInt(member.level());
            packet.writeInt(member.rank());
            packet.writeInt(member.online() ? 1 : 0);
            packet.writeInt(0);     // signature：本期不支持个性签名
            packet.writeInt(0);     // allianceRank：本期不支持联盟
        }
        packet.writeInt(view.capacity());
        packet.writeShort(view.logoBackground());
        packet.writeByte(view.logoBackgroundColor());
        packet.writeShort(view.logoMark());
        packet.writeByte(view.logoMarkColor());
        packet.writeString(view.notice());
        packet.writeInt(view.gp());
        packet.writeInt(view.allianceId());
        return packet;
    }

    /** 邀请（0x05）：公会 id + 被邀请者名字。 */
    public static OutPacket invite(int guildId, String targetName) {
        ByteArrayOutPacket packet = packet(0x05);
        packet.writeInt(guildId);
        packet.writeString(targetName);
        return packet;
    }

    /** 新成员入会（0x27）：id、13 字节名字、职业/等级/职级、在线标志与两个固定字段。 */
    public static OutPacket newMember(int guildId, GuildMemberView member) {
        ByteArrayOutPacket packet = packet(0x27);
        packet.writeInt(guildId);
        packet.writeInt((int) member.characterId());
        writeFixedString(packet, member.name(), NAME_LENGTH);
        packet.writeInt(member.job());
        packet.writeInt(member.level());
        packet.writeInt(member.rank());
        packet.writeInt(member.online() ? 1 : 0);
        packet.writeInt(1);
        packet.writeInt(3);
        return packet;
    }

    /** 成员离开（0x2C 主动退出 / 0x2F 被开除）。 */
    public static OutPacket memberLeft(int guildId, long characterId, String name, boolean expelled) {
        ByteArrayOutPacket packet = packet(expelled ? 0x2F : 0x2C);
        packet.writeInt(guildId);
        packet.writeInt((int) characterId);
        packet.writeString(name);
        return packet;
    }

    /** 解散（0x32）。 */
    public static OutPacket disband(int guildId) {
        ByteArrayOutPacket packet = packet(0x32);
        packet.writeInt(guildId);
        packet.writeByte(1);
        return packet;
    }

    /** 成员上下线（0x3D）。 */
    public static OutPacket memberOnline(int guildId, long characterId, boolean online) {
        ByteArrayOutPacket packet = packet(0x3D);
        packet.writeInt(guildId);
        packet.writeInt((int) characterId);
        packet.writeBool(online);
        return packet;
    }

    /** 公会公告（0x44）。 */
    public static OutPacket notice(int guildId, String notice) {
        ByteArrayOutPacket packet = packet(0x44);
        packet.writeInt(guildId);
        packet.writeString(notice);
        return packet;
    }

    /** 成员职级变化（0x40）。 */
    public static OutPacket changeRank(int guildId, long characterId, int rank) {
        ByteArrayOutPacket packet = packet(0x40);
        packet.writeInt(guildId);
        packet.writeInt((int) characterId);
        packet.writeByte(rank);
        return packet;
    }

    /** 五个职级称号（0x3E）。 */
    public static OutPacket rankTitles(int guildId, List<String> titles) {
        ByteArrayOutPacket packet = packet(0x3E);
        packet.writeInt(guildId);
        for (int i = 0; i < 5; i++) {
            packet.writeString(titles != null && i < titles.size() ? titles.get(i) : "");
        }
        return packet;
    }

    /** 徽章变化（0x42）。 */
    public static OutPacket emblem(int guildId, int background, int backgroundColor, int mark, int markColor) {
        ByteArrayOutPacket packet = packet(0x42);
        packet.writeInt(guildId);
        packet.writeShort(background);
        packet.writeByte(backgroundColor);
        packet.writeShort(mark);
        packet.writeByte(markColor);
        return packet;
    }

    /** GP 变化（0x48）。 */
    public static OutPacket updateGp(int guildId, int gp) {
        ByteArrayOutPacket packet = packet(0x48);
        packet.writeInt(guildId);
        packet.writeInt(gp);
        return packet;
    }

    /** 单字节结果码（例如 28 重名、33 已在公会、41 人数已满、45 不在公会）。 */
    public static OutPacket genericMessage(int code) {
        ByteArrayOutPacket packet = packet(0);
        packet.writeByte(code);
        return packet;
    }

    /** 结果码 + 目标名（拒绝邀请等）。 */
    public static OutPacket responseMessage(int code, String targetName) {
        ByteArrayOutPacket packet = packet(0);
        packet.writeByte(code);
        packet.writeString(targetName);
        return packet;
    }

    /** 同屏角色头顶公会名（独立 opcode 0xCA）。 */
    public static OutPacket nameChanged(long characterId, String guildName) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeShort(SendOpcode.GUILD_NAME_CHANGED.getValue());
        packet.writeInt((int) characterId);
        packet.writeString(guildName == null ? "" : guildName);
        return packet;
    }

    /** 同屏角色公会徽章（独立 opcode 0xCB）。 */
    public static OutPacket markChanged(long characterId, int background, int backgroundColor, int mark, int markColor) {
        ByteArrayOutPacket packet = new ByteArrayOutPacket();
        packet.writeShort(SendOpcode.GUILD_MARK_CHANGED.getValue());
        packet.writeInt((int) characterId);
        packet.writeShort(background);
        packet.writeByte(backgroundColor);
        packet.writeShort(mark);
        packet.writeByte(markColor);
        return packet;
    }

    /** 结果码常量（与参考实现的整数码一致，客户端按数字显示文案）。 */
    public static final int CODE_NAME_TAKEN = 28;
    public static final int CODE_ALREADY_IN_GUILD = 33;
    public static final int CODE_CANNOT_CREATE = 35;
    public static final int CODE_CAPACITY_FULL = 41;
    public static final int CODE_CHARACTER_NOT_FOUND = 42;
    public static final int CODE_NOT_IN_GUILD = 45;
    public static final int CODE_DECLINED_INVITE = 55;

    private static ByteArrayOutPacket packet(int mode) {
        ByteArrayOutPacket packet = GameplayPackets.packet(SendOpcode.GUILD_OPERATION);
        packet.writeByte(mode);
        return packet;
    }

    private static void writeFixedString(OutPacket packet, String value, int length) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(InPacket.DEFAULT_CHARSET);
        packet.writeBytes(Arrays.copyOf(bytes, length));
    }
}
