package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.quest.QuestStatus;
import org.gms.domain.game.skill.SkillEntry;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.OutPacket;
import org.gms.net.packet.v83.V83CharacterPacketWriter;
import org.gms.net.packet.v83.V83CharacterStats;
import org.gms.net.packet.v83.V83FileTime;
import org.gms.net.packet.v83.V83ItemPacketWriter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * v83 频道侧进图包构造（字节级兼容红线 1，包布局对齐参考项目，实现自研）。
 *
 * <p>M2 覆盖进图核心包：getCharInfo（SET_FIELD + addCharacterInfo 全量角色数据）。
 * 地图静态数据由客户端本地 WZ 自取，服务端只发动态数据与角色全量。物品、技能与任务
 * 均由稳定领域状态投影后写入；字节顺序依据 v83 客户端行为与本项目 golden 测试独立固化。
 */
public final class ChannelPacketFactory {

    private ChannelPacketFactory() {
    }

    /** 现金背包槽位上限（v83 固定，与 domain-game Character.CASH_SLOT_LIMIT 一致）。 */
    private static final int CASH_SLOT_LIMIT = 100;

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
     * 登录进图核心包（SendOpcode.SET_FIELD + addCharacterInfo），包含完整背包物品、技能和任务状态。
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
        p.writeLong(V83FileTime.encode(System.currentTimeMillis()));
        return p;
    }

    /* ---------- addCharacterInfo：-1 + 0 + 全量角色数据 ---------- */

    private static void addCharacterInfo(ByteArrayOutPacket p, Character chr) {
        p.writeLong(-1);
        p.writeByte(0);
        V83CharacterPacketWriter.writeStats(p, toProtocolStats(chr));
        p.writeByte(chr.getBuddyCapacity());
        p.writeByte(0);                 // linkedName 无
        p.writeInt(chr.getMeso());
        addInventoryInfo(p, chr);
        addSkillInfo(p, chr);
        addQuestInfo(p, chr);
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
        p.writeLong(V83FileTime.encode(-2));
        List<Item> equipItems = sortedItems(chr, InventoryType.EQUIP);
        // equipped 普通段：-99..-1；现金覆盖装备（<=-100）在下一段
        for (Item item : equipItems) {
            if (item.getPosition() < 0 && item.getPosition() > -100) {
                V83ItemPacketWriter.write(p, ChannelItemProtocolMapper.toSnapshot(item), true);
            }
        }
        p.writeShort(0);                // equipped 结束（equip cash 起始）
        for (Item item : equipItems) {
            if (item.getPosition() <= -100) {
                V83ItemPacketWriter.write(p, ChannelItemProtocolMapper.toSnapshot(item), true);
            }
        }
        p.writeShort(0);                // equip cash 结束（equip 背包起始）
        for (Item item : equipItems) {
            if (item.getPosition() > 0) {
                V83ItemPacketWriter.write(p, ChannelItemProtocolMapper.toSnapshot(item), true);
            }
        }
        p.writeInt(0);                  // equip 背包结束（use 起始）
        writeStackInventory(p, chr, InventoryType.USE);
        p.writeByte(0);                 // use 结束（setup 起始）
        writeStackInventory(p, chr, InventoryType.SETUP);
        p.writeByte(0);                 // setup 结束（etc 起始）
        writeStackInventory(p, chr, InventoryType.ETC);
        p.writeByte(0);                 // etc 结束（cash 起始）
        writeStackInventory(p, chr, InventoryType.CASH);
        // cash 背包无结束标记
    }

    private static void writeStackInventory(ByteArrayOutPacket p, Character chr, InventoryType type) {
        for (Item item : sortedItems(chr, type)) {
            V83ItemPacketWriter.write(p, ChannelItemProtocolMapper.toSnapshot(item), true);
        }
    }

    private static List<Item> sortedItems(Character chr, InventoryType type) {
        List<Item> items = new ArrayList<>(chr.getInventory(type).items());
        items.sort(Comparator.comparingInt(Item::getPosition));
        return items;
    }

    /** v83 技能列表；隐藏的战神派生技能不向客户端重复发送。 */
    private static void addSkillInfo(ByteArrayOutPacket p, Character chr) {
        p.writeByte(0);                 // start of skills
        List<SkillEntry> skills = chr.skills().values().stream()
                .filter(skill -> !isHiddenSkill(skill.skillId()))
                .sorted(Comparator.comparingInt(SkillEntry::skillId))
                .toList();
        p.writeShort(skills.size());
        for (SkillEntry skill : skills) {
            p.writeInt(skill.skillId());
            p.writeInt(skill.level());
            p.writeLong(V83FileTime.encode(skill.expiration()));
            if (isFourthJobSkill(skill.skillId())) {
                p.writeInt(skill.masterLevel());
            }
        }
        p.writeShort(0);                // cooldowns 数
    }

    private static boolean isHiddenSkill(int skillId) {
        return skillId == 21_110_007 || skillId == 21_110_008
                || skillId == 21_120_009 || skillId == 21_120_010;
    }

    private static boolean isFourthJobSkill(int skillId) {
        int jobId = skillId / 10_000;
        if (jobId == 2212) {
            return false;
        }
        return skillId == 22_170_001 || skillId == 22_171_003 || skillId == 22_171_004
                || skillId == 22_181_002 || skillId == 22_181_003 || jobId % 10 == 2;
    }

    /** v83 进行中任务进度 + 已完成任务时间。 */
    private static void addQuestInfo(ByteArrayOutPacket p, Character chr) {
        List<QuestStatus> started = chr.quests().values().stream()
                .filter(status -> status.getState() == QuestStatus.State.STARTED)
                .sorted(Comparator.comparingInt(QuestStatus::getQuestId))
                .toList();
        Map<Integer, QuestStatus> all = chr.quests();
        int startedEntries = started.size();
        for (QuestStatus status : started) {
            if (status.getInfoNumber() > 0 && all.containsKey(status.getInfoNumber())) {
                startedEntries++;
            }
        }
        p.writeShort(startedEntries);
        for (QuestStatus status : started) {
            p.writeShort(status.getQuestId());
            p.writeString(status.progressData());
            QuestStatus info = all.get(status.getInfoNumber());
            if (status.getInfoNumber() > 0 && info != null) {
                p.writeShort(status.getInfoNumber());
                p.writeString(info.progressData());
            }
        }

        List<QuestStatus> completed = all.values().stream()
                .filter(status -> status.getState() == QuestStatus.State.COMPLETED)
                .sorted(Comparator.comparingInt(QuestStatus::getQuestId))
                .toList();
        p.writeShort(completed.size());
        for (QuestStatus status : completed) {
            p.writeShort(status.getQuestId());
            p.writeLong(V83FileTime.encode(status.getCompletionTime()));
        }
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

    private static V83CharacterStats toProtocolStats(Character chr) {
        return new V83CharacterStats(
                (int) chr.getId(), chr.getName(), chr.getGender(), chr.getSkinColor(), chr.getFace(), chr.getHair(),
                chr.getLevel(), chr.getJob(), chr.getStr(), chr.getDex(), chr.getIntStat(), chr.getLuk(),
                chr.getHp(), chr.getMaxHp(), chr.getMp(), chr.getMaxMp(), chr.getAp(), chr.getSp(),
                chr.getExp(), chr.getFame(), chr.getGachaExp(), chr.getMap(), chr.getSpawnPoint());
    }
}
