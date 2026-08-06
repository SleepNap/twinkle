package org.gms.login;

import org.gms.data.entity.Character;
import org.gms.net.opcodes.SendOpcode;
import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.InPacket;
import org.gms.net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录响应包字节布局测试（红线 1：v83 包字节级兼容）。
 */
class LoginPacketFactoryTest {

    @Test
    void loginStatusFailed_layout() {
        OutPacket p = LoginPacketFactory.loginStatusFailed(4);
        ByteArrayInPacket in = new ByteArrayInPacket(p.getBytes());

        assertThat(in.readUnsignedShort()).isEqualTo(SendOpcode.LOGIN_STATUS.getValue()); // 0x00
        assertThat(in.readByte()).isEqualTo((byte) 4);  // reason
        assertThat(in.readByte()).isZero();
        assertThat(in.readInt()).isZero();
        assertThat(in.available()).isZero();
    }

    @Test
    void loginStatusSuccess_layout() {
        OutPacket p = LoginPacketFactory.loginStatusSuccess(42, 1, "alice");
        ByteArrayInPacket in = new ByteArrayInPacket(p.getBytes());

        assertThat(in.readUnsignedShort()).isEqualTo(SendOpcode.LOGIN_STATUS.getValue());
        assertThat(in.readInt()).isZero();      // status 0
        assertThat(in.readUnsignedShort()).isZero();
        assertThat(in.readInt()).isEqualTo(42); // account id
        assertThat(in.readByte()).isEqualTo((byte) 1); // gender
        assertThat(in.readByte()).isZero();     // GM
        assertThat(in.readByte()).isZero();     // admin
        assertThat(in.readByte()).isZero();     // country
        assertThat(in.readString()).isEqualTo("alice");
        assertThat(in.readByte()).isZero();     // accountName 后补字节
        assertThat(in.readByte()).isZero();     // IsQuietBan
        assertThat(in.readLong()).isZero();
        assertThat(in.readLong()).isZero();
        assertThat(in.readInt()).isEqualTo(1);  // 移除世界选择
        assertThat(in.readByte()).isEqualTo((byte) 1); // PIN 关闭
        assertThat(in.readByte()).isEqualTo((byte) 2); // PIC 关闭
        assertThat(in.available()).isZero();
    }

    @Test
    void serverListAndEnd_layout() {
        OutPacket p = LoginPacketFactory.serverList(0, "twinkle");
        ByteArrayInPacket in = new ByteArrayInPacket(p.getBytes());

        assertThat(in.readUnsignedShort()).isEqualTo(SendOpcode.SERVERLIST.getValue()); // 0x0A
        assertThat(in.readByte()).isZero();      // serverId
        assertThat(in.readString()).isEqualTo("twinkle");
        assertThat(in.readByte()).isZero();      // flag
        assertThat(in.readString()).isEmpty();   // event message
        assertThat(in.readByte()).isEqualTo((byte) 100);
        assertThat(in.readByte()).isZero();
        assertThat(in.readByte()).isEqualTo((byte) 100);
        assertThat(in.readByte()).isZero();
        assertThat(in.readByte()).isZero();
        assertThat(in.readByte()).isEqualTo((byte) 1); // 频道数
        assertThat(in.readString()).isEqualTo("twinkle-1");
        assertThat(in.readInt()).isEqualTo(100); // 频道容量
        assertThat(in.readByte()).isZero();      // worldId
        assertThat(in.readByte()).isZero();      // channelId - 1
        assertThat(in.readByte()).isZero();      // 非成人频道
        assertThat(in.readUnsignedShort()).isZero(); // 列表结束 short
        assertThat(in.available()).isZero();

        ByteArrayInPacket end = new ByteArrayInPacket(LoginPacketFactory.endOfServerList().getBytes());
        assertThat(end.readUnsignedShort()).isEqualTo(SendOpcode.SERVERLIST.getValue());
        assertThat(end.readByte()).isEqualTo((byte) 0xFF);
        assertThat(end.available()).isZero();
    }

    @Test
    void charList_layoutWithSingleCharacter() {
        Character c = new Character();
        c.setId(100L);
        c.setName("Hero");
        c.setGender(0);
        c.setSkincolor(3);
        c.setFace(20000);
        c.setHair(30000);
        c.setLevel(10);
        c.setJob(0);
        c.setStr((short) 4);
        c.setDex((short) 5);
        c.setIntStat((short) 4);
        c.setLuk((short) 4);
        c.setHp((short) 50);
        c.setMp((short) 5);
        c.setMaxhp((short) 50);
        c.setMaxmp((short) 5);
        c.setAp((short) 0);
        c.setExp(0L);
        c.setFame(0);
        c.setMap(100000000);
        c.setSpawnpoint(0);

        OutPacket p = LoginPacketFactory.charList(List.of(c), 0, 0);
        ByteArrayInPacket in = new ByteArrayInPacket(p.getBytes());

        assertThat(in.readUnsignedShort()).isEqualTo(SendOpcode.CHARLIST.getValue()); // 0x0B
        assertThat(in.readByte()).isZero();      // status
        assertThat(in.readByte()).isEqualTo((byte) 1); // 角色数

        // addCharStats
        assertThat(in.readInt()).isEqualTo(100); // char id
        byte[] nameBytes = in.readBytes(13);     // 13 字节定长名
        assertThat(new String(nameBytes, InPacket.DEFAULT_CHARSET).trim()).isEqualTo("Hero");
        assertThat(in.readByte()).isEqualTo((byte) 0); // gender
        assertThat(in.readByte()).isEqualTo((byte) 3); // skin
        assertThat(in.readInt()).isEqualTo(20000);     // face
        assertThat(in.readInt()).isEqualTo(30000);     // hair
        in.readLong(); in.readLong(); in.readLong();   // 宠物 x3
        assertThat(in.readByte()).isEqualTo((byte) 10); // level
        assertThat(in.readUnsignedShort()).isZero();    // job
        assertThat(in.readUnsignedShort()).isEqualTo(4); // str
        assertThat(in.readUnsignedShort()).isEqualTo(5); // dex
        assertThat(in.readUnsignedShort()).isEqualTo(4); // int
        assertThat(in.readUnsignedShort()).isEqualTo(4); // luk
        assertThat(in.readUnsignedShort()).isEqualTo(50); // hp
        assertThat(in.readUnsignedShort()).isEqualTo(50); // maxhp
        assertThat(in.readUnsignedShort()).isEqualTo(5);  // mp
        assertThat(in.readUnsignedShort()).isEqualTo(5);  // maxmp
        assertThat(in.readUnsignedShort()).isZero();      // ap
        assertThat(in.readUnsignedShort()).isZero();      // remainingSp
        assertThat(in.readInt()).isZero();                // exp
        assertThat(in.readUnsignedShort()).isZero();      // fame
        assertThat(in.readInt()).isZero();                // gachaExp
        assertThat(in.readInt()).isEqualTo(100000000);    // map
        assertThat(in.readByte()).isZero();               // spawnpoint
        assertThat(in.readInt()).isZero();                // 尾部占位

        // addCharLook（空装备）
        assertThat(in.readByte()).isEqualTo((byte) 0); // gender
        assertThat(in.readByte()).isEqualTo((byte) 3); // skin
        assertThat(in.readInt()).isEqualTo(20000);     // face
        assertThat(in.readByte()).isEqualTo((byte) 1); // !mega
        assertThat(in.readInt()).isEqualTo(30000);     // hair
        assertThat(in.readByte()).isEqualTo((byte) 0xFF); // 普通装备结束
        assertThat(in.readByte()).isEqualTo((byte) 0xFF); // masked 装备结束
        assertThat(in.readInt()).isZero();                // 武器
        assertThat(in.readInt()).isZero();                // 宠物 x3
        assertThat(in.readInt()).isZero();
        assertThat(in.readInt()).isZero();

        // addCharEntry 尾部
        assertThat(in.readByte()).isZero();               // viewall 额外字节
        assertThat(in.readByte()).isEqualTo((byte) 1);    // 排名启用
        assertThat(in.readInt()).isZero();                // rank
        assertThat(in.readInt()).isZero();                // rankMove
        assertThat(in.readInt()).isZero();                // jobRank
        assertThat(in.readInt()).isZero();                // jobRankMove

        // CHARLIST 尾部
        assertThat(in.readByte()).isEqualTo((byte) 2);    // PIC 关闭
        assertThat(in.readInt()).isEqualTo(3);            // 角色槽位
        assertThat(in.available()).isZero();
    }

    @Test
    void serverIp_layout() {
        OutPacket p = LoginPacketFactory.serverIp(new byte[]{127, 0, 0, 1}, 8484, 7);
        ByteArrayInPacket in = new ByteArrayInPacket(p.getBytes());

        assertThat(in.readUnsignedShort()).isEqualTo(SendOpcode.SERVER_IP.getValue()); // 0x0C
        assertThat(in.readUnsignedShort()).isZero();
        assertThat(in.readBytes(4)).containsExactly(127, 0, 0, 1);
        assertThat(in.readUnsignedShort()).isEqualTo(8484);
        assertThat(in.readInt()).isEqualTo(7);
        assertThat(in.readBytes(5)).containsExactly(0, 0, 0, 0, 0);
        assertThat(in.available()).isZero();
    }

    @Test
    void fixedStringPadding_isNullPaddedTo13Bytes() {
        // 中文名 GBK 多字节也应右补 \0 到 13 字节
        Character c = new Character();
        c.setId(1L);
        c.setName("冒险家");
        OutPacket p = LoginPacketFactory.charList(List.of(c), 0, 0);
        ByteArrayInPacket in = new ByteArrayInPacket(p.getBytes());
        in.readUnsignedShort(); // opcode
        in.readByte();          // status
        in.readByte();          // count
        in.readInt();           // id
        byte[] nameBytes = in.readBytes(13);
        assertThat(nameBytes.length).isEqualTo(13);
        Charset gbk = Charset.forName("GBK");
        assertThat(nameBytes).startsWith("冒险家".getBytes(gbk));
        int padding = 13 - "冒险家".getBytes(gbk).length;
        for (int i = nameBytes.length - padding; i < nameBytes.length; i++) {
            assertThat(nameBytes[i]).isZero();
        }
    }
}
