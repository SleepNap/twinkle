package org.gms.parity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 录包回放基建测试（架构 M0 第 8 项 / M1 落地）：录制 → 回放 → 比对。
 */
class PacketReplayTest {

    private static byte[] sample(int len, byte seed) {
        byte[] d = new byte[len];
        for (int i = 0; i < len; i++) {
            d[i] = (byte) (seed + i);
        }
        return d;
    }

    @Test
    void recorderCollectsAndSnapshots() {
        InMemoryPacketRecorder recorder = new InMemoryPacketRecorder();
        recorder.record(new PacketReplaySupport.PacketRecord(1L, sample(4, (byte) 1)));
        recorder.record(new PacketReplaySupport.PacketRecord(2L, sample(8, (byte) 2)));

        assertThat(recorder.size()).isEqualTo(2);
        assertThat(recorder.snapshot()).hasSize(2);
        assertThat(recorder.snapshot().get(0).payload()).containsExactly(1, 2, 3, 4);

        recorder.clear();
        assertThat(recorder.size()).isZero();
    }

    @Test
    void replayerDeliversPacketsInOrder() {
        List<byte[]> received = new ArrayList<>();
        PacketSink sink = received::add;
        DefaultPacketReplayer replayer = new DefaultPacketReplayer(sink);

        List<PacketReplaySupport.PacketRecord> packets = List.of(
                new PacketReplaySupport.PacketRecord(1L, sample(2, (byte) 7)),
                new PacketReplaySupport.PacketRecord(2L, sample(3, (byte) 8)));
        replayer.replay(packets);

        assertThat(received).hasSize(2);
        assertThat(received.get(0)).containsExactly(7, 8);
        assertThat(received.get(1)).containsExactly(8, 9, 10);
    }

    @Test
    void differIdentifiesNoDiffWhenIdentical() {
        List<byte[]> expected = List.of(sample(4, (byte) 1), sample(6, (byte) 2));
        List<byte[]> actual = List.of(sample(4, (byte) 1), sample(6, (byte) 2));

        assertThat(PacketDiffer.firstDiff(expected, actual)).isEmpty();
        assertThat(PacketDiffer.identical(sample(4, (byte) 1), sample(4, (byte) 1))).isTrue();
    }

    @Test
    void differFindsByteMismatch() {
        List<byte[]> expected = List.of(sample(4, (byte) 1));
        byte[] altered = sample(4, (byte) 1);
        altered[2] = 99;
        List<byte[]> actual = List.of(altered);

        PacketDiffer.Diff diff = PacketDiffer.firstDiff(expected, actual).orElseThrow();
        assertThat(diff.index()).isEqualTo(0);
        assertThat(diff.actual()[2]).isEqualTo((byte) 99);
    }

    @Test
    void differFindsCountMismatch() {
        List<byte[]> expected = List.of(sample(4, (byte) 1), sample(4, (byte) 2));
        List<byte[]> actual = List.of(sample(4, (byte) 1));

        PacketDiffer.Diff diff = PacketDiffer.firstDiff(expected, actual).orElseThrow();
        assertThat(diff.index()).isEqualTo(1);
    }
}
