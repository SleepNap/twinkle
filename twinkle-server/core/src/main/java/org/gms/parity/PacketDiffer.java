package org.gms.parity;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 包序列字节比对器（架构 M2 parity：行为对齐参考项目 = 回包字节级一致）。
 *
 * <p>黑盒比对：不解析包内容，只比字节。M1 用于编解码自洽验证，M2 用于
 * 本服务端回包 vs 参考项目（北斗）回包逐字节对齐。
 */
public final class PacketDiffer {

    /** 首个差异。index = 序列下标；expected/actual 为该位置两边的包负载。 */
    public record Diff(int index, byte[] expected, byte[] actual) {
    }

    private PacketDiffer() {
    }

    /**
     * 找到首个差异。序列完全一致返回 {@link Optional#empty()}。
     */
    public static Optional<Diff> firstDiff(List<byte[]> expected, List<byte[]> actual) {
        int n = Math.min(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            if (!Arrays.equals(expected.get(i), actual.get(i))) {
                return Optional.of(new Diff(i, expected.get(i), actual.get(i)));
            }
        }
        if (expected.size() != actual.size()) {
            // 数量不同：无同位置可比（expected 更长或 actual 更长）
            return Optional.of(new Diff(n, null, null));
        }
        return Optional.empty();
    }

    /**
     * 单包字节是否完全一致。
     */
    public static boolean identical(byte[] expected, byte[] actual) {
        return Arrays.equals(expected, actual);
    }
}
