package org.gms.net.encryption;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 会话初始向量（v83 握手协议）。
 *
 * <p>v83 客户端与服务端各持两个 IV：发送 IV 与接收 IV（互为镜像）。握手包内
 * 发送给客户端两份 IV 的字节，客户端据此初始化自身收发方向。
 *
 * <p>字节格式约定：前 3 字节固定（发送侧 "Rx"、接收侧 "Frz"），第 4 字节随机。
 * 这是 v83 协议事实常量，改动会破坏握手（红线 1）。
 *
 * <p>思路参考自 BeiDou-Server（OdinMS 系），实现自研。
 */
public final class InitializationVector {

    private final byte[] bytes;

    private InitializationVector(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] getBytes() {
        return bytes.clone();
    }

    /**
     * 服务端发送方向 IV（握手时发给客户端）。
     */
    public static InitializationVector generateSend() {
        return new InitializationVector(new byte[]{82, 48, 120, randomByte()});
    }

    /**
     * 服务端接收方向 IV（握手时发给客户端）。
     */
    public static InitializationVector generateReceive() {
        return new InitializationVector(new byte[]{70, 114, 122, randomByte()});
    }

    /**
     * 从原始字节重建（4 字节）。握手解析 / 测试重建会话用。
     */
    public static InitializationVector of(byte[] bytes) {
        if (bytes == null || bytes.length != 4) {
            throw new IllegalArgumentException("IV 必须恰好 4 字节");
        }
        return new InitializationVector(bytes.clone());
    }

    private static byte randomByte() {
        return (byte) ThreadLocalRandom.current().nextInt(256);
    }
}
