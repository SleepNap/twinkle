package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;

import java.util.TimeZone;

/**
 * v83 频道侧进图包构造（字节级兼容红线 1，包布局对齐参考项目，实现自研）。
 *
 * <p>M2 覆盖进图核心包：getCharInfo（SET_FIELD + addCharacterInfo 全量角色数据）。
 * 地图静态数据由客户端本地 WZ 自取，服务端只发动态数据与角色全量（思路参考自
 * BeiDou-Server 的 PacketCreator，按 twinkle 空背包/技能/任务占位裁剪自研）。
 */
public final class ChannelPacketFactory {

    private ChannelPacketFactory() {
    }

    /** 现金背包槽位上限（v83 固定，与 domain-game Character.CASH_SLOT_LIMIT 一致）。 */
    private static final int CASH_SLOT_LIMIT = 100;

    /* ---------- v83 filetime：100ns 自 1601-01-01；特殊值 -1/-2/-3 ---------- */

    private static final long FT_UT_OFFSET = 116444736010800000L
            + (10000L * TimeZone.getDefault().getOffset(System.currentTimeMillis()));
    private static final long DEFAULT_TIME = 150842304000000000L;
    private static final long ZERO_TIME = 94354848000000000L;
    private static final long PERMANENT = 150841440000000000L;

    private static long getTime(long utcTimestamp) {
        if (utcTimestamp < 0 && utcTimestamp >= -3) {
            if (utcTimestamp == -1) {
                return DEFAULT_TIME;
            } else if (utcTimestamp == -2) {
                return ZERO_TIME;
            } else {
                return PERMANENT;
            }
        }
        return utcTimestamp * 10000 + FT_UT_OFFSET;
    }

    /**
     * 服务器公告（SendOpcode.SERVERMESSAGE 0x44）：3 类型 + 短字符串内容。
     * 广播用（喇叭/活动公告，架构 4.4 消息总线）。思路参考 BeiDou 的 PacketCreator.serverNotice。
     */
    public static OutPacket serverNotice(String message) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.SERVERMESSAGE.getValue());
        p.writeByte(4); // 公告类型（4 = 顶部滚动）
        p.writeString(message);
        return p;
    }

    /** v83 地图聊天回包：角色 ID + GM 标志 + 文本 + 客户端显示标志。 */
    public static OutPacket chatText(long characterId, boolean gm, String message, int show) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.CHATTEXT.getValue());
        p.writeInt((int) characterId);
        p.writeBool(gm);
        p.writeString(message);
        p.writeByte(show);
        return p;
    }

    /**
     * 登录进图核心包（SendOpcode.SET_FIELD + addCharacterInfo）。
     * 空背包/技能/任务等段按 v83 空列表写法占位。
     */
    public static OutPacket charInfo(Character chr, int channelId) {
        ByteArrayOutPacket p = new ByteArrayOutPacket();
        p.writeShort(SendOpcode.SET_FIELD.getValue());
        p.writeInt(channelId - 1);
        p.writeByte(1);
        p.writeByte(1);
        p.writeShort(0);
        for (int i = 0; i < 3; i++) {
            p.writeInt(0);              // 客户端进度随机数，占位
        }
        addCharacterInfo(p, chr);
        p.writeLong(getTime(System.currentTimeMillis()));
        return p;
    }

    /* ---------- addCharacterInfo：-1 + 0 + 全量角色数据 ---------- */

    private static void addCharacterInfo(ByteArrayOutPacket p, Character chr) {
        p.writeLong(-1);
        p.writeByte(0);
        addCharStats(p, chr);
        p.writeByte(chr.getBuddyCapacity());
        p.writeByte(0);                 // linkedName 无
        p.writeInt(chr.getMeso());
        addInventoryInfo(p, chr);
        addSkillInfo(p);
        addQuestInfo(p);
        p.writeShort(0);                // miniGame
        // v83 addRingInfo：crush rings + friendship rings + marriageRing 三段各一个 short
        // （参考项目 partnerId<=0 时也写 writeShort(0)，缺此段会让后续字段错位 2 字节导致客户端断连）
        p.writeShort(0);                // crush rings
        p.writeShort(0);                // friendship rings
        p.writeShort(0);                // marriageRing 段（partnerId<=0 无戒指）
        addTeleportInfo(p);
        addMonsterBookInfo(p, chr);
        p.writeShort(0);                // newYear
        p.writeShort(0);                // areaInfo
        p.writeShort(0);                // 结尾
    }

    private static void addCharStats(ByteArrayOutPacket p, Character chr) {
        p.writeInt((int) chr.getId());
        writeFixedString(p, chr.getName(), 13);
        p.writeByte(chr.getGender());
        p.writeByte(chr.getSkinColor());
        p.writeInt(chr.getFace());
        p.writeInt(chr.getHair());
        p.writeLong(0);                 // 宠物 x3
        p.writeLong(0);
        p.writeLong(0);
        p.writeByte(chr.getLevel());
        p.writeShort(chr.getJob());
        p.writeShort(chr.getStr());
        p.writeShort(chr.getDex());
        p.writeShort(chr.getIntStat());
        p.writeShort(chr.getLuk());
        p.writeShort(chr.getHp());
        p.writeShort(chr.getMaxHp());
        p.writeShort(chr.getMp());
        p.writeShort(chr.getMaxMp());
        p.writeShort(chr.getAp());
        p.writeShort(remainingSp(chr)); // 非 Aran 职业用 short
        p.writeInt((int) chr.getExp());
        p.writeShort(chr.getFame());
        p.writeInt((int) chr.getGachaExp());
        p.writeInt(chr.getMap());
        p.writeByte(chr.getSpawnPoint());
        p.writeInt(0);
    }

    /**
     * 背包信息：5 槽位上限 + 时间 + 各背包段。已穿戴装备（EQUIP 背包负位置行）
     * 用 addItemInfo 编码进 equipped 段（思路参考 BeiDou addInventoryInfo）。
     */
    private static void addInventoryInfo(ByteArrayOutPacket p, Character chr) {
        p.writeByte(chr.getEquipSlots());
        p.writeByte(chr.getUseSlots());
        p.writeByte(chr.getSetupSlots());
        p.writeByte(chr.getEtcSlots());
        p.writeByte(CASH_SLOT_LIMIT);
        p.writeLong(getTime(-2));       // ZERO_TIME
        // equipped 段：EQUIP 背包中 position < 0（已穿戴）的行
        for (Item item : chr.getInventory(InventoryType.EQUIP).items()) {
            if (item.getPosition() >= 0) {
                continue;
            }
            addEquipItemInfo(p, item);
        }
        p.writeShort(0);                // equipped 结束（equip cash 起始）
        p.writeShort(0);                // equip cash 结束（equip 背包起始）
        // equip 背包段（未穿戴，M2-2 前为空）
        p.writeInt(0);                  // equip 背包结束（use 起始）
        p.writeByte(0);                 // use 结束（setup 起始）
        p.writeByte(0);                 // setup 结束（etc 起始）
        p.writeByte(0);                 // etc 结束（cash 起始）
        // cash 背包无结束标记
    }

    /**
     * 已穿戴装备项编码（v83 addItemInfo 的装备分支，属性从 WZ 取默认 0——M2-2 装备
     * 属性系统落地前按 0 编码，客户端仍能正确显示外观）。
     *
     * <p>布局：short 槽位（负转正）+ byte 类型1 + int 物品id + bool cash + 过期时间 +
     * byte 升级槽 + byte 等级 + short×14 属性 + owner + flag + 非 cash 属性段 + 时间 + int -1。
     */
    private static void addEquipItemInfo(ByteArrayOutPacket p, Item item) {
        short pos = item.getPosition();
        p.writeShort((short) Math.abs(pos));   // 槽位正数
        p.writeByte(1);                        // 类型 EQUIP
        p.writeInt(item.getId());
        p.writeBool(false);                    // 非现金
        p.writeLong(getTime(-2));              // 过期（永久）
        p.writeByte(0);                        // 升级槽
        p.writeByte(0);                        // 装备等级
        p.writeShort(0);                       // str
        p.writeShort(0);                       // dex
        p.writeShort(0);                       // int
        p.writeShort(0);                       // luk
        p.writeShort(0);                       // hp
        p.writeShort(0);                       // mp
        p.writeShort(0);                       // watk
        p.writeShort(0);                       // matk
        p.writeShort(0);                       // wdef
        p.writeShort(0);                       // mdef
        p.writeShort(0);                       // acc
        p.writeShort(0);                       // avoid
        p.writeShort(0);                       // hands
        p.writeShort(0);                       // speed
        p.writeShort(0);                       // jump
        p.writeString("");                     // owner
        p.writeShort(0);                       // flag
        // 非现金：itemLevel 段（0 + 0 + exp 0 + vicious 0 + long 0）
        p.writeByte(0);
        p.writeByte(0);
        p.writeInt(0);
        p.writeInt(0);
        p.writeLong(0);
        p.writeLong(getTime(-2));              // 过期
        p.writeInt(-1);
    }

    /** 空技能。 */
    private static void addSkillInfo(ByteArrayOutPacket p) {
        p.writeByte(0);                 // start of skills
        p.writeShort(0);                // 技能数
        p.writeShort(0);                // cooldowns 数
    }

    /** 空任务。 */
    private static void addQuestInfo(ByteArrayOutPacket p) {
        p.writeShort(0);                // 进行中任务数
        p.writeShort(0);                // 已完成任务数
    }

    /** 空传送记录：5 trock + 10 vip。 */
    private static void addTeleportInfo(ByteArrayOutPacket p) {
        for (int i = 0; i < 5; i++) {
            p.writeInt(0);
        }
        for (int i = 0; i < 10; i++) {
            p.writeInt(0);
        }
    }

    private static void addMonsterBookInfo(ByteArrayOutPacket p, Character chr) {
        p.writeInt(chr.getMonsterBookCover());
        p.writeByte(0);
        p.writeShort(0);                // 卡片数
    }

    /** Aran 系职业用 SP 表（M2 简化为统一 short remainingSp，与选角包一致）。 */
    private static short remainingSp(Character chr) {
        String sp = chr.getSp();
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

    private static void writeFixedString(ByteArrayOutPacket p, String value, int fixed) {
        byte[] bytes = value.getBytes(InPacket.DEFAULT_CHARSET);
        byte[] out = new byte[fixed];
        System.arraycopy(bytes, 0, out, 0, Math.min(bytes.length, fixed));
        p.writeBytes(out);
    }
}
