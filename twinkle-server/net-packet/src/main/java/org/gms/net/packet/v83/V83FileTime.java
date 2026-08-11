package org.gms.net.packet.v83;

/** v83 使用的 Windows FILETIME 编码（100ns，自 1601-01-01 起）。 */
public final class V83FileTime {

    /** Unix 纪元到 Windows FILETIME 纪元的固定 UTC 差值。 */
    private static final long UNIX_OFFSET = 116444736000000000L;
    private static final long DEFAULT_TIME = 150842304000000000L;
    private static final long ZERO_TIME = 94354848000000000L;
    private static final long PERMANENT_TIME = 150841440000000000L;

    private V83FileTime() {
    }

    /**
     * 编码毫秒时间戳；-1/-2/-3 是 v83 协议约定的默认、零值和永久时间。
     */
    public static long encode(long timestamp) {
        if (timestamp == -1) {
            return DEFAULT_TIME;
        }
        if (timestamp == -2) {
            return ZERO_TIME;
        }
        if (timestamp == -3) {
            return PERMANENT_TIME;
        }
        return timestamp * 10000 + UNIX_OFFSET;
    }
}
