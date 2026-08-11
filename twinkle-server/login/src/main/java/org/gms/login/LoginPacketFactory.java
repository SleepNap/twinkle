package org.gms.login;

import org.gms.data.entity.Character;
import org.gms.data.entity.InventoryItemEntity;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.v83.V83CharacterLook;
import org.gms.net.packet.v83.V83CharacterPacketWriter;
import org.gms.net.packet.v83.V83CharacterStats;
import org.gms.net.packet.v83.V83EquippedItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
     * 服务器状态响应（RecvOpcode.SERVERSTATUS_REQUEST 的回复）。
     * status：0=正常 / 1=繁忙 / 2=满（M1 单世界恒 0）。
     */
    public static OutPacket serverStatus(int status) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.SERVERSTATUS.getValue());
        p.writeShort(status);
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
     *
     * @param equippedByChar 角色 id → 已穿戴装备（inventory_items position&lt;0 行，可为空）
     */
    public static OutPacket charList(List<Character> characters, int serverId, int status,
                                     Map<Long, List<InventoryItemEntity>> equippedByChar) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.CHARLIST.getValue());
        p.writeByte(status);
        p.writeByte(characters.size());
        for (Character c : characters) {
            List<InventoryItemEntity> equipped = equippedByChar == null
                    ? null : equippedByChar.get(c.getId());
            addCharEntry(p, c, equipped);
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

    /* ---------- 建角（SendOpcode.CHAR_NAME_RESPONSE / ADD_NEW_CHAR_ENTRY） ---------- */

    /**
     * 角色名检查响应（SendOpcode.CHAR_NAME_RESPONSE 0x0D，建角前置）。
     * {@code nameUsed}：1 = 已占用 / 0 = 可用（思路参考 BeiDou charNameResponse）。
     */
    public static OutPacket charNameResponse(String name, boolean nameUsed) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.CHAR_NAME_RESPONSE.getValue());
        p.writeString(name);
        p.writeByte(nameUsed ? 1 : 0);
        return p;
    }

    /**
     * 新建角色条目（SendOpcode.ADD_NEW_CHAR_ENTRY 0x0E，建角成功）。
     * 首字节 0（成功）；随后角色条目布局与选角列表一致（思路参考 BeiDou addNewCharEntry）。
     *
     * @param equipped 新角色已穿戴装备（建角默认装备，客户端立即显示全身外观）
     */
    public static OutPacket addNewCharEntry(Character c, List<InventoryItemEntity> equipped) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.ADD_NEW_CHAR_ENTRY.getValue());
        p.writeByte(0);
        addCharEntry(p, c, equipped);
        return p;
    }

    /**
     * 新建角色条目（无装备，兼容旧调用）。
     */
    public static OutPacket addNewCharEntry(Character c) {
        return addNewCharEntry(c, null);
    }

    /**
     * 建角失败（SendOpcode.DELETE_CHAR_RESPONSE 0x0F 复用做建角错误弹窗）。
     * {@code state} 语义：9 = 未知错误（参考 BeiDou deleteCharResponse 的建角失败路径）。
     */
    public static OutPacket createCharFailed(int state) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.DELETE_CHAR_RESPONSE.getValue());
        p.writeInt(0);
        p.writeByte(state);
        return p;
    }

    /* ---------- 查看所有角色（SendOpcode.VIEW_ALL_CHAR 0x08） ---------- */

    /**
     * 查看所有角色总览头（"查看所有角色"界面的 world 数 + 角色总数）。
     * 首个 byte：1=有角色 / 5=找不到任何角色（思路参考 BeiDou showAllCharacter）。
     */
    public static OutPacket showAllCharacter(int totalWorlds, int totalChrs) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.VIEW_ALL_CHAR.getValue());
        p.writeByte(totalChrs > 0 ? 1 : 5);
        p.writeInt(totalWorlds);
        p.writeInt(totalChrs);
        return p;
    }

    /**
     * 查看所有角色：单个 world 的角色列表段（总览头之后逐 world 发）。
     * 每条目用 viewall=true 布局（无普通列表的额外 1 字节，思路参考 BeiDou showAllCharacterInfo）。
     */
    public static OutPacket showAllCharacterInfo(int worldId, List<Character> characters,
                                                 Map<Long, List<InventoryItemEntity>> equippedByChar) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.VIEW_ALL_CHAR.getValue());
        p.writeByte(0);             // 段标记
        p.writeByte(worldId);
        p.writeByte(characters.size());
        for (Character c : characters) {
            List<InventoryItemEntity> equipped = equippedByChar == null
                    ? null : equippedByChar.get(c.getId());
            addCharEntry(p, c, equipped, true);
        }
        p.writeByte(2);             // PIC 关闭
        return p;
    }

    /* ---------- 角色条目编码（addCharStats + addCharLook + rank） ---------- */

    private static void addCharEntry(OutPacket p, Character c, List<InventoryItemEntity> equipped) {
        addCharEntry(p, c, equipped, false);
    }

    private static void addCharEntry(OutPacket p, Character c, List<InventoryItemEntity> equipped, boolean viewall) {
        V83CharacterPacketWriter.writeStats(p, toProtocolStats(c));
        V83CharacterPacketWriter.writeLook(p, toProtocolLook(c, equipped), false);
        if (!viewall) {
            p.writeByte(0);         // 普通列表的额外字节（查看所有角色模式省略）
        }
        p.writeByte(1);             // 世界排名启用
        p.writeInt((int) c.getRank());
        p.writeInt(c.getRankMove());
        p.writeInt((int) c.getJobRank());
        p.writeInt(c.getJobRankMove());
    }

    private static V83CharacterStats toProtocolStats(Character c) {
        return new V83CharacterStats(
                c.getId().intValue(), c.getName(), c.getGender(), c.getSkinColor(), c.getFace(), c.getHair(),
                c.getLevel(), c.getJob(), c.getStr(), c.getDex(), c.getIntStat(), c.getLuk(),
                c.getHp(), c.getMaxHp(), c.getMp(), c.getMaxMp(), c.getAp(), c.getSp(),
                c.getExp(), c.getFame(), c.getGachaExp(), c.getMap(), c.getSpawnPoint());
    }

    private static V83CharacterLook toProtocolLook(Character c, List<InventoryItemEntity> equipped) {
        List<V83EquippedItem> items = new ArrayList<>();
        if (equipped != null) {
            for (InventoryItemEntity item : equipped) {
                items.add(new V83EquippedItem(item.getPosition(), item.getItemId()));
            }
        }
        return new V83CharacterLook(c.getGender(), c.getSkinColor(), c.getFace(), c.getHair(), items);
    }
}
