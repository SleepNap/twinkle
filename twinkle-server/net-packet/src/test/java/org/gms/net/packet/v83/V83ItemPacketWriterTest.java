package org.gms.net.packet.v83;

import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.gms.net.packet.InPacket;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** v83 公共物品字节段 golden 测试。 */
class V83ItemPacketWriterTest {

    @Test
    void stackItem_writesPositionQuantityOwnerAndFlag() {
        V83ItemSnapshot item = new V83ItemSnapshot(3, 2, 2000000, 0, 0, 25, "Hero", 7, null);
        ByteArrayOutPacket out = new ByteArrayOutPacket();

        V83ItemPacketWriter.write(out, item, true);

        InPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readByte()).isEqualTo((byte) 3);
        assertThat(in.readByte()).isEqualTo((byte) 2);
        assertThat(in.readInt()).isEqualTo(2000000);
        assertThat(in.readByte()).isZero();
        assertThat(in.readLong()).isEqualTo(V83FileTime.encode(-2));
        assertThat(in.readShort()).isEqualTo((short) 25);
        assertThat(in.readString()).isEqualTo("Hero");
        assertThat(in.readShort()).isEqualTo((short) 7);
        assertThat(in.available()).isZero();
    }

    @Test
    void equip_writesShortPositionAndAllStats() {
        V83EquipStats stats = new V83EquipStats(
                7, 2, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 1, 4, 1234);
        V83ItemSnapshot item = new V83ItemSnapshot(-5, 1, 1040002, 0, 0, 1, "Maker", 3, stats);
        ByteArrayOutPacket out = new ByteArrayOutPacket();

        V83ItemPacketWriter.write(out, item, true);

        InPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readShort()).isEqualTo((short) 5);
        assertThat(in.readByte()).isEqualTo((byte) 1);
        assertThat(in.readInt()).isEqualTo(1040002);
        assertThat(in.readByte()).isZero();
        assertThat(in.readLong()).isEqualTo(V83FileTime.encode(-2));
        assertThat(in.readByte()).isEqualTo((byte) 7);
        assertThat(in.readByte()).isEqualTo((byte) 2);
        for (short expected = 1; expected <= 15; expected++) {
            assertThat(in.readShort()).isEqualTo(expected);
        }
        assertThat(in.readString()).isEqualTo("Maker");
        assertThat(in.readShort()).isEqualTo((short) 3);
        assertThat(in.readByte()).isZero();
        assertThat(in.readByte()).isEqualTo((byte) 4);
        assertThat(in.readInt()).isEqualTo(1234);
        assertThat(in.readInt()).isEqualTo(1);
        assertThat(in.readLong()).isZero();
        assertThat(in.readLong()).isEqualTo(V83FileTime.encode(-2));
        assertThat(in.readInt()).isEqualTo(-1);
        assertThat(in.available()).isZero();
    }

    @Test
    void tradeMode_omitsInventoryPosition() {
        V83ItemSnapshot item = new V83ItemSnapshot(8, 2, 4000000, 0, 0, 2, "", 0, null);
        ByteArrayOutPacket out = new ByteArrayOutPacket();

        V83ItemPacketWriter.write(out, item, false);

        InPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readByte()).isEqualTo((byte) 2);
        assertThat(in.readInt()).isEqualTo(4000000);
    }

    @Test
    void pet_writesUniqueIdAndCompletePetState() {
        V83PetStats pet = new V83PetStats("小黑", 12, 3456, 87, 3, 4, 17_500, 5);
        V83ItemSnapshot item = new V83ItemSnapshot(
                2, 2, 5000000, 9001, 1_800_000_000_000L, 1, "", 0, null, pet);
        ByteArrayOutPacket out = new ByteArrayOutPacket();

        V83ItemPacketWriter.write(out, item, true);

        InPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readByte()).isEqualTo((byte) 2);
        assertThat(in.readByte()).isEqualTo((byte) 2);
        assertThat(in.readInt()).isEqualTo(5_000_000);
        assertThat(in.readByte()).isEqualTo((byte) 1);
        assertThat(in.readLong()).isEqualTo(9001L);
        assertThat(in.readLong()).isEqualTo(V83FileTime.encode(1_800_000_000_000L));
        byte[] name = in.readBytes(13);
        assertThat(name).startsWith("小黑".getBytes(InPacket.DEFAULT_CHARSET));
        assertThat(name[12]).isZero();
        assertThat(in.readByte()).isEqualTo((byte) 12);
        assertThat(in.readShort()).isEqualTo((short) 3456);
        assertThat(in.readByte()).isEqualTo((byte) 87);
        assertThat(in.readLong()).isEqualTo(V83FileTime.encode(1_800_000_000_000L));
        assertThat(in.readShort()).isEqualTo((short) 3);
        assertThat(in.readShort()).isEqualTo((short) 4);
        assertThat(in.readInt()).isEqualTo(17_500);
        assertThat(in.readShort()).isEqualTo((short) 5);
        assertThat(in.available()).isZero();
    }
}
