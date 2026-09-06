package org.gms.net.packet.v83;

import org.gms.net.packet.ByteArrayInPacket;
import org.gms.net.packet.ByteArrayOutPacket;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 手写协议样本验证片段边界，不依赖外部服务端资源。 */
public class V83MovementTest {
    @Test public void mixedFragmentsPreserveSignedPositionAndExcludeClientTrailer() {
        var out = new ByteArrayOutPacket(); out.writeByte(3);
        out.writeByte(0).writeShort(-123).writeShort(-456).writeInt(0).writeShort(9).writeByte(6).writeShort(30);
        out.writeByte(1).writeShort(100).writeShort(-200).writeByte(5).writeShort(40);
        out.writeByte(10).writeByte(1);
        byte[] stream = out.getBytes(); out.writeInt(0x12345678);
        var result = V83Movement.read(new ByteArrayInPacket(out.getBytes()));
        assertThat(result).isNotNull();
        assertThat(result.x()).isEqualTo(-123); assertThat(result.y()).isEqualTo(-456);
        assertThat(result.stance()).isEqualTo(5); assertThat(result.foothold()).isEqualTo(9);
        assertThat(result.bytes()).isEqualTo(stream);
        result.bytes()[0] = 0; assertThat(result.bytes()[0]).isEqualTo((byte) 3);
    }

    @Test public void everySupportedFragmentRejectsEveryTruncatedBoundary() {
        Map<Integer, Integer> sizes = Map.ofEntries(Map.entry(0, 13), Map.entry(5, 13), Map.entry(17, 13),
                Map.entry(1, 7), Map.entry(2, 7), Map.entry(6, 7), Map.entry(12, 7), Map.entry(13, 7),
                Map.entry(16, 7), Map.entry(18, 7), Map.entry(19, 7), Map.entry(20, 7), Map.entry(22, 7),
                Map.entry(3, 9), Map.entry(4, 9), Map.entry(7, 9), Map.entry(8, 9), Map.entry(9, 9),
                Map.entry(11, 9), Map.entry(14, 9), Map.entry(15, 15), Map.entry(10, 1), Map.entry(21, 3));
        sizes.forEach((command, size) -> {
            byte[] bytes = new byte[size + 2]; bytes[0] = 1; bytes[1] = command.byteValue();
            assertThat(V83Movement.read(new ByteArrayInPacket(bytes))).isNotNull();
            for (int end = 0; end < bytes.length; end++) {
                assertThat(V83Movement.read(new ByteArrayInPacket(Arrays.copyOf(bytes, end))))
                        .as("command=%s length=%s", command, end).isNull();
            }
            bytes[0] = 2; assertThat(V83Movement.read(new ByteArrayInPacket(bytes))).isNull();
        });
        assertThat(V83Movement.read(new ByteArrayInPacket(new byte[]{1, 99}))).isNull();
    }
}
