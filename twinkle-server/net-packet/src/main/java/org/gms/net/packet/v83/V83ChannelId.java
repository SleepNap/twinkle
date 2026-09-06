package org.gms.net.packet.v83;

/** v83 频道 ID 边界编码；内部 ID 稳定且可稀疏，wire 使用无符号 byte 的 internal-1。 */
public final class V83ChannelId {
    public static final int MIN_INTERNAL_ID = 1;
    public static final int MAX_INTERNAL_ID = 256;

    private V83ChannelId() {
    }

    public static int toWire(int internalId) {
        validateInternal(internalId);
        return internalId - 1;
    }

    public static int fromWire(int wireId) {
        if (wireId < 0 || wireId > 255) {
            throw new IllegalArgumentException("v83 wire channel id out of range: " + wireId);
        }
        return wireId + 1;
    }

    public static void validateInternal(int internalId) {
        if (internalId < MIN_INTERNAL_ID || internalId > MAX_INTERNAL_ID) {
            throw new IllegalArgumentException("v83 internal channel id out of range: " + internalId);
        }
    }
}
