package org.gms.net.packet.v83;

import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** v83 角色公共字节段的独立 golden 布局测试。 */
class V83CharacterPacketWriterTest {

    @Test
    void writeStats_usesFixedNameAndStableFieldOrder() {
        V83CharacterStats stats = new V83CharacterStats(
                7, "冒险家", 1, 2, 20001, 30001,
                30, 100, 12, 13, 14, 15,
                1234, 2345, 345, 456, 6, "9,8,7",
                123456, 17, 654321, 100000000, 3);
        ByteArrayOutPacket out = new ByteArrayOutPacket();

        V83CharacterPacketWriter.writeStats(out, stats);

        InPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readInt()).isEqualTo(7);
        byte[] fixedName = in.readBytes(13);
        assertThat(fixedName).startsWith("冒险家".getBytes(InPacket.DEFAULT_CHARSET));
        assertThat(fixedName[12]).isZero();
        assertThat(in.readByte()).isEqualTo((byte) 1);
        assertThat(in.readByte()).isEqualTo((byte) 2);
        assertThat(in.readInt()).isEqualTo(20001);
        assertThat(in.readInt()).isEqualTo(30001);
        assertThat(in.readLong()).isZero();
        assertThat(in.readLong()).isZero();
        assertThat(in.readLong()).isZero();
        assertThat(in.readByte()).isEqualTo((byte) 30);
        assertThat(in.readShort()).isEqualTo((short) 100);
        assertThat(in.readShort()).isEqualTo((short) 12);
        assertThat(in.readShort()).isEqualTo((short) 13);
        assertThat(in.readShort()).isEqualTo((short) 14);
        assertThat(in.readShort()).isEqualTo((short) 15);
        assertThat(in.readShort()).isEqualTo((short) 1234);
        assertThat(in.readShort()).isEqualTo((short) 2345);
        assertThat(in.readShort()).isEqualTo((short) 345);
        assertThat(in.readShort()).isEqualTo((short) 456);
        assertThat(in.readShort()).isEqualTo((short) 6);
        assertThat(in.readShort()).isEqualTo((short) 9);
        assertThat(in.readInt()).isEqualTo(123456);
        assertThat(in.readShort()).isEqualTo((short) 17);
        assertThat(in.readInt()).isEqualTo(654321);
        assertThat(in.readInt()).isEqualTo(100000000);
        assertThat(in.readByte()).isEqualTo((byte) 3);
        assertThat(in.readInt()).isZero();
        assertThat(in.available()).isZero();
    }

    @Test
    void writeLook_sortsSlotsAndSeparatesWeaponField() {
        V83CharacterLook look = new V83CharacterLook(
                0, 3, 20000, 30000,
                List.of(
                        new V83EquippedItem(-7, 1072001),
                        new V83EquippedItem(-11, 1302000),
                        new V83EquippedItem(-5, 1040002)));
        ByteArrayOutPacket out = new ByteArrayOutPacket();

        V83CharacterPacketWriter.writeLook(out, look, false);

        InPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readByte()).isZero();
        assertThat(in.readByte()).isEqualTo((byte) 3);
        assertThat(in.readInt()).isEqualTo(20000);
        assertThat(in.readByte()).isEqualTo((byte) 1);
        assertThat(in.readInt()).isEqualTo(30000);
        assertThat(in.readByte()).isEqualTo((byte) 5);
        assertThat(in.readInt()).isEqualTo(1040002);
        assertThat(in.readByte()).isEqualTo((byte) 7);
        assertThat(in.readInt()).isEqualTo(1072001);
        assertThat(in.readByte()).isEqualTo((byte) 0xFF);
        assertThat(in.readByte()).isEqualTo((byte) 0xFF);
        assertThat(in.readInt()).isEqualTo(1302000);
        assertThat(in.readInt()).isZero();
        assertThat(in.readInt()).isZero();
        assertThat(in.readInt()).isZero();
        assertThat(in.available()).isZero();
    }
}
