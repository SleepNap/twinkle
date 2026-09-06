package org.gms.net.packet.v83;

/** v83 大区 ID 协议边界。0xFF 是服务器列表结束标记，因此可用 ID 为 0..254，允许稀疏。 */
public final class V83WorldId {

    public static final int MIN = 0;
    public static final int MAX = 254;

    private V83WorldId() {
    }

    public static int validate(int worldId) {
        if (worldId < MIN || worldId > MAX) {
            throw new IllegalArgumentException("worldId must be representable by v83 (0..254): " + worldId);
        }
        return worldId;
    }
}
