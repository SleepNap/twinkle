package org.gms.login;

import org.gms.data.entity.Character;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;

import java.util.List;

/**
 * v83 登录侧响应包构造（字节级兼容红线 1，包布局对齐参考项目，实现自研）。
 *
 * <p>覆盖 M1 登录链路：LOGIN_STATUS（成败）、SERVERLIST、CHARLIST、SERVER_IP。
 * 字符集用 {@link InPacket#DEFAULT_CHARSET}（GBK，中服约定）；角色名 13 字节定长。
 *
 * <p>思路参考自 BeiDou-Server（OdinMS 系）的 PacketCreator 登录部分，按 twinkle
 * 需求裁剪自研。
 */
public final class LoginPacketFactory {

    private LoginPacketFactory() {
    }

    /* ---------- 登录状态（SendOpcode.LOGIN_STATUS 0x00） ---------- */

    /**
     * 登录失败。reason 语义（v83）：3/2=封禁、4=密码错误、5=账号不存在、7=已登录、14=网关错误等。
     */
    public static OutPacket loginStatusFailed(int reason) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.LOGIN_STATUS.getValue());
        p.writeByte(reason);
        p.writeByte(0);
        p.writeInt(0);
        return p;
    }

    /**
     * 登录成功。GM/管理位置 false，PIN/PIC 关闭（M1 无 PIN/PIC 系统）。
     */
    public static OutPacket loginStatusSuccess(long accountId, int gender, String accountName) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.LOGIN_STATUS.getValue());
        p.writeInt(0);              // 状态 0 = 成功
        p.writeShort(0);
        p.writeInt((int) accountId);
        p.writeByte(gender);
        p.writeBool(false);         // GM
        p.writeByte(0);             // admin byte
        p.writeByte(0);             // country code
        p.writeString(accountName);
        p.writeByte(0);
        p.writeByte(0);             // IsQuietBan
        p.writeLong(0);             // IsQuietBanTimeStamp
        p.writeLong(0);             // CreationTimeStamp
        p.writeInt(1);              // 1: 移除"选择世界"界面
        p.writeByte(1);             // PIN 关闭
        p.writeByte(2);             // PIC 关闭
        return p;
    }

    /* ---------- 服务器列表（SendOpcode.SERVERLIST 0x0A） ---------- */

    /**
     * 单个服务器条目。M1 单世界单频道简化。
     */
    public static OutPacket serverList(int serverId, String serverName) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.SERVERLIST.getValue());
        p.writeByte(serverId);
        p.writeString(serverName);
        p.writeByte(0);             // flag
        p.writeString("");          // event message
        p.writeByte(100);           // 倍率修饰
        p.writeByte(0);
        p.writeByte(100);
        p.writeByte(0);
        p.writeByte(0);
        p.writeByte(1);             // 频道数（M1 单频道）
        p.writeString(serverName + "-1");
        p.writeInt(100);            // 频道容量
        p.writeByte(serverId);
        p.writeByte(0);             // channelId - 1
        p.writeBool(false);         // 非成人频道
        p.writeShort(0);
        return p;
    }

    /**
     * 服务器列表结束标记。
     */
    public static OutPacket endOfServerList() {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.SERVERLIST.getValue());
        p.writeByte(0xFF);
        return p;
    }

    /* ---------- 角色列表（SendOpcode.CHARLIST 0x0B） ---------- */

    /**
     * 角色列表（选角）。status 0 = 成功；末尾 PIC 模式 2（关闭）+ 角色槽位 3。
     */
    public static OutPacket charList(List<Character> characters, int serverId, int status) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.CHARLIST.getValue());
        p.writeByte(status);
        p.writeByte(characters.size());
        for (Character c : characters) {
            addCharEntry(p, c);
        }
        p.writeByte(2);             // PIC 关闭
        p.writeInt(3);              // 角色槽位
        return p;
    }

    /* ---------- 服务器 IP（SendOpcode.SERVER_IP 0x0C） ---------- */

    /**
     * 选角成功 → 客户端连频道服（M1 回本进程地址，进图留 M2）。
     */
    public static OutPacket serverIp(byte[] ip, int port, int clientId) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.SERVER_IP.getValue());
        p.writeShort(0);
        p.writeBytes(ip);
        p.writeShort(port);
        p.writeInt(clientId);
        p.writeBytes(new byte[]{0, 0, 0, 0, 0});
        return p;
    }

    /* ---------- 角色条目编码（addCharStats + addCharLook + rank） ---------- */

    private static void addCharEntry(OutPacket p, Character c) {
        addCharStats(p, c);
        addCharLook(p, c);
        p.writeByte(0);             // viewall 分支的额外字节
        p.writeByte(1);             // 世界排名启用
        p.writeInt((int) c.getRank());
        p.writeInt(c.getRankMove());
        p.writeInt((int) c.getJobRank());
        p.writeInt(c.getJobRankMove());
    }

    private static void addCharStats(OutPacket p, Character c) {
        p.writeInt(c.getId().intValue());
        writeFixedString(p, c.getName(), 13);
        p.writeByte(c.getGender());
        p.writeByte(c.getSkincolor());
        p.writeInt(c.getFace());
        p.writeInt(c.getHair());
        p.writeLong(0);             // 宠物 x3
        p.writeLong(0);
        p.writeLong(0);
        p.writeByte(c.getLevel());
        p.writeShort(c.getJob());
        p.writeShort(c.getStr());
        p.writeShort(c.getDex());
        p.writeShort(c.getIntStat());
        p.writeShort(c.getLuk());
        p.writeShort(c.getHp());
        p.writeShort(c.getMaxhp());
        p.writeShort(c.getMp());
        p.writeShort(c.getMaxmp());
        p.writeShort(c.getAp());
        p.writeShort(remainingSp(c)); // 非 Aran 职业用 short
        p.writeInt((int) c.getExp());
        p.writeShort(c.getFame());
        p.writeInt((int) c.getGachaexp());
        p.writeInt(c.getMap());
        p.writeByte(c.getSpawnpoint());
        p.writeInt(0);
    }

    private static void addCharLook(OutPacket p, Character c) {
        p.writeByte(c.getGender());
        p.writeByte(c.getSkincolor());
        p.writeInt(c.getFace());
        p.writeBool(true);          // !mega
        p.writeInt(c.getHair());
        // 装备（M1 角色无 inventory 数据 → 空装备列表，字节级对齐空列表写法）
        p.writeByte(0xFF);          // 普通装备结束
        p.writeByte(0xFF);          // masked 装备结束
        p.writeInt(0);              // 武器
        p.writeInt(0);              // 宠物 x3
        p.writeInt(0);
        p.writeInt(0);
    }

    /**
     * Aran 系职业用 SP 表（M1 简化为统一 short remainingSp，测试角色均为新手职业）。
     */
    private static short remainingSp(Character c) {
        String sp = c.getSp();
        if (sp == null || sp.isEmpty()) {
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

    private static void writeFixedString(OutPacket p, String value, int fixed) {
        byte[] bytes = value.getBytes(InPacket.DEFAULT_CHARSET);
        byte[] out = new byte[fixed];
        System.arraycopy(bytes, 0, out, 0, Math.min(bytes.length, fixed));
        p.writeBytes(out);
    }
}
