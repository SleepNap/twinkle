package org.gms.net.netty;

import org.gms.diagnostics.PacketTrace;
import org.gms.net.opcodes.RecvOpcode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 单玩家封包监听的过滤、安全脱敏、停止和有界截断测试。 */
public final class PacketTraceBufferTest {

    @Test
    public void excludeModeDropsNoiseAndKeepsOtherInboundPackets() {
        PacketTraceBuffer buffer = new PacketTraceBuffer(new PacketTrace.Config(
                PacketTrace.FilterMode.EXCLUDE, Set.of(PacketTrace.Direction.INBOUND),
                Set.of("MOVE_LIFE"), 64));

        buffer.capture(PacketTrace.Direction.INBOUND, packet(RecvOpcode.MOVE_LIFE.getValue(), 8));
        buffer.capture(PacketTrace.Direction.INBOUND, packet(RecvOpcode.ITEM_MOVE.getValue(), 8));
        buffer.capture(PacketTrace.Direction.OUTBOUND, packet(0x1234, 8));

        PacketTrace.Snapshot snapshot = buffer.snapshot(0, 20);
        assertThat(snapshot.events()).singleElement().satisfies(event -> {
            assertThat(event.direction()).isEqualTo(PacketTrace.Direction.INBOUND);
            assertThat(event.opcodeName()).isEqualTo("ITEM_MOVE");
        });
    }

    @Test
    public void includeModeSupportsUnknownHexOpcodeAndTruncatesPayload() {
        PacketTraceBuffer buffer = new PacketTraceBuffer(new PacketTrace.Config(
                PacketTrace.FilterMode.INCLUDE, Set.of(PacketTrace.Direction.INBOUND),
                Set.of("0X1234"), 64));

        buffer.capture(PacketTrace.Direction.INBOUND, packet(0x1234, 100));

        assertThat(buffer.snapshot(0, 20).events()).singleElement().satisfies(event -> {
            assertThat(event.opcodeName()).isEqualTo("UNKNOWN_0x1234");
            assertThat(event.packetLength()).isEqualTo(100);
            assertThat(event.capturedLength()).isEqualTo(64);
            assertThat(event.truncated()).isTrue();
        });
    }

    @Test
    public void credentialsAreNeverCapturedAndStopIsImmediate() {
        PacketTraceBuffer buffer = new PacketTraceBuffer(new PacketTrace.Config(
                PacketTrace.FilterMode.INCLUDE, Set.of(PacketTrace.Direction.INBOUND),
                Set.of("LOGIN_PASSWORD", "ITEM_MOVE"), 128));

        buffer.capture(PacketTrace.Direction.INBOUND, packet(RecvOpcode.LOGIN_PASSWORD.getValue(), 32));
        buffer.capture(PacketTrace.Direction.INBOUND, packet(RecvOpcode.ITEM_MOVE.getValue(), 12));
        buffer.stop();
        buffer.capture(PacketTrace.Direction.INBOUND, packet(RecvOpcode.ITEM_MOVE.getValue(), 12));

        PacketTrace.Snapshot snapshot = buffer.snapshot(0, 20);
        assertThat(snapshot.enabled()).isFalse();
        assertThat(snapshot.events()).singleElement()
                .extracting(PacketTrace.Event::opcodeName).isEqualTo("ITEM_MOVE");
    }

    private static byte[] packet(int opcode, int length) {
        byte[] packet = new byte[length];
        packet[0] = (byte) opcode;
        packet[1] = (byte) (opcode >>> 8);
        for (int i = 2; i < length; i++) {
            packet[i] = (byte) i;
        }
        return packet;
    }
}
