package org.gms.net.packet;

import org.gms.net.encryption.AesCipher;
import org.gms.net.encryption.InitializationVector;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 包读写 + 编解码字节级验证（红线 1：v83 小端字节序 + 字符串 short 长度前缀）。
 */
class PacketCodecTest {

    @Test
    void outPacketWritesLittleEndian() {
        ByteArrayOutPacket out = new ByteArrayOutPacket();
        out.writeShort(0x1234);
        out.writeInt(0x0BC614E); // 12345678

        byte[] bytes = out.getBytes();
        assertThat(bytes).containsExactly(
                0x34, 0x12,                      // short LE
                (byte) 0x4E, 0x61, (byte) 0xBC, 0x00); // int LE
    }

    @Test
    void stringRoundTripWithDefaultCharset() {
        ByteArrayOutPacket out = new ByteArrayOutPacket();
        String chinese = "欢迎来到twinkle";
        out.writeString(chinese);

        ByteArrayInPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readString()).isEqualTo(chinese);
    }

    @Test
    void inPacketReadsLongAndSkip() {
        ByteArrayOutPacket out = new ByteArrayOutPacket();
        out.writeLong(0x1122334455667788L);
        out.skip(3);
        out.writeBool(true);

        ByteArrayInPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readLong()).isEqualTo(0x1122334455667788L);
        in.skip(3);
        assertThat(in.readByte()).isEqualTo((byte) 1);
    }

    @Test
    void readStringWithExplicitCharset() {
        ByteArrayOutPacket out = new ByteArrayOutPacket();
        out.writeString("hello", StandardCharsets.US_ASCII);

        ByteArrayInPacket in = new ByteArrayInPacket(out.getBytes());
        assertThat(in.readString(StandardCharsets.US_ASCII)).isEqualTo("hello");
    }

    @Test
    void codecEncodesAndDecodes() {
        InitializationVector iv = InitializationVector.generateSend();
        AesCipher send = new AesCipher(iv, (short) 83);
        AesCipher recv = new AesCipher(iv, (short) 83);

        ByteArrayOutPacket payload = new ByteArrayOutPacket();
        payload.writeShort(0x0B);   // SERVERLIST_REQUEST
        payload.writeString("twinkle");

        byte[] wire = PacketCodec.encodePacket(send, payload.getBytes());
        // 线包 = 4 字节 header + 加密负载
        assertThat(wire.length).isEqualTo(4 + payload.getBytes().length);

        int header = ((wire[0] & 0xFF) << 24) | ((wire[1] & 0xFF) << 16)
                | ((wire[2] & 0xFF) << 8) | (wire[3] & 0xFF);
        int length = AesCipher.decodePacketLength(header);
        assertThat(length).isEqualTo(payload.getBytes().length);

        byte[] body = new byte[length];
        System.arraycopy(wire, 4, body, 0, length);
        InPacket decoded = PacketCodec.decodePacket(recv, header, body);

        assertThat(decoded.readUnsignedShort()).isEqualTo(0x0B);
        assertThat(decoded.readString()).isEqualTo("twinkle");
    }

    @Test
    void codecRejectsCorruptedHeader() {
        InitializationVector iv = InitializationVector.generateSend();
        AesCipher send = new AesCipher(iv, (short) 83);
        AesCipher recv = new AesCipher(iv, (short) 83);

        byte[] payload = new byte[]{0x01, 0x02};
        PacketCodec.encodePacket(send, payload);

        // 用一个从未同步的接收方（不同 IV）解码 → header 校验失败
        InitializationVector other = InitializationVector.generateReceive();
        AesCipher rogue = new AesCipher(other, (short) 83);
        byte[] bogusHeaderBytes = rogue.packetHeader(2);
        int bogusHeader = ((bogusHeaderBytes[0] & 0xFF) << 24) | ((bogusHeaderBytes[1] & 0xFF) << 16)
                | ((bogusHeaderBytes[2] & 0xFF) << 8) | (bogusHeaderBytes[3] & 0xFF);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> PacketCodec.decodePacket(recv, bogusHeader, payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("header");
    }
}
