package org.gms.net.packet.v83;

import org.gms.net.packet.InPacket;

import java.util.Arrays;

/**
 * v83 移动片段边界与最终动作投影。长度核对来源：BeiDou-Server AbstractMovementPacketHandler。
 * 先验证所有片段，成功后才返回可用于落地/转发的结果；未知或截断片段整包拒绝。
 */
public record V83Movement(byte[] bytes, Integer x, Integer y, Integer stance, Integer foothold) {
    public V83Movement { bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }

    /** 输入停在片段数量处；末尾客户端附带数据不属于片段流，不转发。 */
    public static V83Movement read(InPacket input) {
        if (input.available() < 1) return null;
        int start = input.getBytes().length - input.available();
        int count = input.readByte() & 255;
        if (count == 0 || count > 127) return null;
        Integer x = null, y = null, stance = null, foothold = null;
        for (int index = 0; index < count; index++) {
            if (input.available() < 1) return null;
            int command = input.readByte() & 255;
            int length = switch (command) {
                case 0, 5, 17 -> 13;
                case 1, 2, 6, 12, 13, 16, 18, 19, 20, 22 -> 7;
                case 3, 4, 7, 8, 9, 11, 14 -> 9;
                case 15 -> 15;
                case 10 -> 1;
                case 21 -> 3;
                default -> -1;
            };
            if (length < 0 || input.available() < length) return null;
            byte[] body = input.readBytes(length);
            switch (command) {
                case 0, 5, 17, 15 -> {
                    x = signedShort(body, 0); y = signedShort(body, 2);
                    foothold = signedShort(body, 8);
                    stance = body[command == 15 ? 12 : 10] & 255;
                }
                case 3, 4, 7, 8, 9, 11 -> {
                    x = signedShort(body, 0); y = signedShort(body, 2); stance = body[8] & 255;
                }
                case 1, 2, 6, 12, 13, 16, 18, 19, 20, 22 -> {
                    // 相对片段携带速度，不当作坐标增量累加；绝对片段才更新位置。
                    stance = body[4] & 255;
                }
                default -> { }
            }
        }
        byte[] raw = input.getBytes();
        return new V83Movement(Arrays.copyOfRange(raw, start, raw.length - input.available()), x, y, stance, foothold);
    }

    private static int signedShort(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8);
    }
}
