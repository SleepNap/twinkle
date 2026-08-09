package org.gms.channel;

import org.gms.domain.game.Character;
import org.gms.domain.game.inventory.Item;
import org.gms.domain.game.inventory.Inventory;
import org.gms.domain.game.inventory.InventoryType;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChannelPacketFactory.charInfo（SET_FIELD + addCharacterInfo）字节布局验证。
 *
 * <p>golden 真值 = 参考项目 v83 addCharacterInfo 的完整段序。此前缺失"结婚戒指段"
 * （marriageRing writeShort(0)）导致后续字段错位 2 字节，真机客户端解析失败断连
 * （error code -38 类）。本测试逐段断言，防回归。
 */
class ChannelPacketFactoryLayoutTest {

    @Test
    void charInfo_addCharacterInfo_matchesReferenceLayout() throws Exception {
        Character chr = new Character(1);
        chr.setId(1L);
        chr.setName("Hero");
        chr.setGender(0);
        chr.setSkinColor(0);
        chr.setFace(20000);
        chr.setHair(30000);
        chr.setLevel(1);
        chr.setJob(0);
        chr.setMap(10000);
        chr.setSpawnPoint(0);
        chr.setBuddyCapacity(25);
        chr.setMeso(0);
        chr.clearDirty();

        // 一件已穿戴装备（上衣 1040002，位置 -5）
        Inventory equip = chr.getInventory(InventoryType.EQUIP);
        Item top = new Item(1040002);
        top.setPosition((short) -5);
        equip.putAtSlot((short) -5, top);

        OutPacket packet = ChannelPacketFactory.charInfo(chr, 1);
        InPacket p = new ByteArrayInPacket(packet.getBytes());

        // SET_FIELD 头
        assertThat(p.readUnsignedShort()).isEqualTo(0x7D);      // SET_FIELD
        assertThat(p.readInt()).isZero();                        // channel-1
        assertThat(p.readByte()).isEqualTo((byte) 1);
        assertThat(p.readByte()).isEqualTo((byte) 1);
        p.readShort();                                           // 0
        p.readInt(); p.readInt(); p.readInt();                   // 3 随机数

        // addCharacterInfo：long -1 + byte 0
        assertThat(p.readLong()).isEqualTo(-1);
        assertThat(p.readByte()).isZero();

        // addCharStats
        assertThat(p.readInt()).isEqualTo(1);                    // id
        assertThat(new String(p.readBytes(13), "UTF-8").trim()).isEqualTo("Hero");
        assertThat(p.readByte()).isEqualTo((byte) 0);            // gender
        assertThat(p.readByte()).isZero();                       // skinColor
        assertThat(p.readInt()).isEqualTo(20000);                // face
        assertThat(p.readInt()).isEqualTo(30000);                // hair
        p.readBytes(24);                                         // 宠物 x3
        assertThat(p.readByte()).isEqualTo((byte) 1);            // level
        assertThat(p.readShort()).isEqualTo((short) 0);          // job
        assertThat(p.readShort()).isEqualTo((short) 12);         // str
        assertThat(p.readShort()).isEqualTo((short) 5);          // dex
        assertThat(p.readShort()).isEqualTo((short) 4);          // int
        assertThat(p.readShort()).isEqualTo((short) 4);          // luk
        assertThat(p.readShort()).isEqualTo((short) 50);         // hp
        assertThat(p.readShort()).isEqualTo((short) 50);         // maxHp
        assertThat(p.readShort()).isEqualTo((short) 5);          // mp
        assertThat(p.readShort()).isEqualTo((short) 5);          // maxMp
        assertThat(p.readShort()).isZero();                      // ap
        assertThat(p.readShort()).isZero();                      // sp
        assertThat(p.readInt()).isZero();                        // exp
        assertThat(p.readShort()).isZero();                      // fame
        assertThat(p.readInt()).isZero();                        // gachaExp
        assertThat(p.readInt()).isEqualTo(10000);                // map
        assertThat(p.readByte()).isZero();                       // spawnPoint
        assertThat(p.readInt()).isZero();                        // 尾部 int

        // buddy + linkedName + meso
        assertThat(p.readByte()).isEqualTo((byte) 25);           // buddyCapacity
        assertThat(p.readByte()).isZero();                       // linkedName
        assertThat(p.readInt()).isZero();                        // meso

        // addInventoryInfo：5 slot + long ZERO + equipped(1件) + cash + equip 背包 + use/setup/etc
        assertThat(p.readByte()).isEqualTo((byte) 24);           // equipSlots
        assertThat(p.readByte()).isEqualTo((byte) 24);           // useSlots
        assertThat(p.readByte()).isEqualTo((byte) 24);           // setupSlots
        assertThat(p.readByte()).isEqualTo((byte) 24);           // etcSlots
        assertThat(p.readByte()).isEqualTo((byte) 100);          // cashSlots
        p.readLong();                                            // ZERO_TIME

        // equipped 段：1 件装备（short 槽位 + byte 类型 + int id + ... 完整装备块）
        assertThat(p.readShort()).isEqualTo((short) 5);          // 槽位正数
        assertThat(p.readByte()).isEqualTo((byte) 1);            // 类型 EQUIP
        assertThat(p.readInt()).isEqualTo(1040002);              // 上衣 id
        // 装备属性块（bool cash + long + 2 byte + 15 short + owner + flag + 非cash段 + long + int）
        p.readBytes(75);

        // equipped 结束 → equip cash 起始
        assertThat(p.readShort()).isZero();
        // equip cash 结束 → equip 背包起始
        assertThat(p.readShort()).isZero();
        // equip 背包结束 → use 起始
        assertThat(p.readInt()).isZero();
        // use/setup/etc 结束
        assertThat(p.readByte()).isZero();
        assertThat(p.readByte()).isZero();
        assertThat(p.readByte()).isZero();

        // addSkillInfo：byte 0 + short 0 + short 0
        assertThat(p.readByte()).isZero();
        assertThat(p.readShort()).isZero();
        assertThat(p.readShort()).isZero();

        // addQuestInfo：short 0 + short 0
        assertThat(p.readShort()).isZero();
        assertThat(p.readShort()).isZero();

        // miniGame：short 0
        assertThat(p.readShort()).isZero();

        // addRingInfo：crush + friendship + marriage 三段各 short（参考项目必有 marriage 段）
        assertThat(p.readShort()).isZero();   // crush rings
        assertThat(p.readShort()).isZero();   // friendship rings
        assertThat(p.readShort()).isZero();   // marriageRing 段

        // addTeleportInfo：5 + 10 int
        for (int i = 0; i < 15; i++) {
            assertThat(p.readInt()).isZero();
        }

        // addMonsterBookInfo：int + byte + short
        assertThat(p.readInt()).isZero();
        assertThat(p.readByte()).isZero();
        assertThat(p.readShort()).isZero();

        // newYear + areaInfo + 结尾
        assertThat(p.readShort()).isZero();
        assertThat(p.readShort()).isZero();
        assertThat(p.readShort()).isZero();

        // 结尾 filetime long
        p.readLong();
        // 无剩余字节（若 RING 段缺 1 short 会在此露出）
        assertThat(p.available()).isZero();
    }
}
