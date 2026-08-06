package org.gms.net.packet;

import java.nio.charset.Charset;

/**
 * 包读取接口（v83 小端字节序）。
 *
 * <p>v83 客户端协议包内整数一律小端（Little-Endian）；字符串为
 * {@code short 长度（字节数，无符号） + 按会话字符集编码的字节}。
 *
 * <p>默认字符集 {@link #DEFAULT_CHARSET} = GBK（中文服务端约定，与 v83 客户端
 * 区域 locale 对齐）。需要按会话语言覆盖时使用带 charset 的重载。
 */
public interface InPacket {

    /** 字符串编码默认值：GBK（中服约定）。 */
    Charset DEFAULT_CHARSET = Charset.forName("GBK");

    byte readByte();

    /**
     * 无符号 short（0~65535）。
     */
    int readUnsignedShort();

    /**
     * 有符号 short，按 {@code int} 返回（兼容负数）。
     */
    short readShort();

    int readInt();

    long readLong();

    /**
     * 读字符串：short 长度 + 字节（默认 {@link #DEFAULT_CHARSET}）。
     */
    String readString();

    /**
     * 读字符串：按指定字符集解码。
     */
    String readString(Charset charset);

    byte[] readBytes(int count);

    void skip(int count);

    /**
     * 剩余可读字节数。
     */
    int available();

    byte[] getBytes();
}
