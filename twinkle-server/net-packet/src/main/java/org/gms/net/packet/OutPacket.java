package org.gms.net.packet;

import java.nio.charset.Charset;

/**
 * 包写入接口（v83 小端字节序）。
 *
 * <p>与 {@link InPacket} 对称：整数小端写入；字符串先写 short 长度（字节数）再写
 * 编码字节（默认 {@link InPacket#DEFAULT_CHARSET} = GBK）。
 */
public interface OutPacket {

    OutPacket writeByte(int value);

    OutPacket writeShort(int value);

    OutPacket writeInt(int value);

    OutPacket writeLong(long value);

    OutPacket writeBool(boolean value);

    /**
     * 写字符串：short 长度 + 字节（默认 {@link InPacket#DEFAULT_CHARSET}）。
     */
    OutPacket writeString(String value);

    /**
     * 写字符串：按指定字符集编码。
     */
    OutPacket writeString(String value, Charset charset);

    OutPacket writeBytes(byte[] bytes);

    /**
     * 跳过 n 字节（写入 0）。
     */
    OutPacket skip(int count);

    /**
     * 当前写入长度。
     */
    int length();

    byte[] getBytes();
}
