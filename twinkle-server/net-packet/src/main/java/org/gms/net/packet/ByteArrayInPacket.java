package org.gms.net.packet;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * 包读取实现（小端字节序，自研）。
 *
 * <p>包装一段负载字节，顺序读取。读取越界由 {@code ArrayIndexOutOfBoundsException} 自然暴露
 * （包结构错误是协议缺陷，快速失败优先于静默错读）。
 */
public final class ByteArrayInPacket implements InPacket {

    private final byte[] buf;
    private int readerIndex;

    public ByteArrayInPacket(byte[] payload) {
        this.buf = payload;
    }

    @Override
    public byte readByte() {
        return buf[readerIndex++];
    }

    @Override
    public int readUnsignedShort() {
        return (buf[readerIndex++] & 0xFF) | ((buf[readerIndex++] & 0xFF) << 8);
    }

    @Override
    public short readShort() {
        return (short) readUnsignedShort();
    }

    @Override
    public int readInt() {
        return (buf[readerIndex++] & 0xFF)
                | ((buf[readerIndex++] & 0xFF) << 8)
                | ((buf[readerIndex++] & 0xFF) << 16)
                | ((buf[readerIndex++] & 0xFF) << 24);
    }

    @Override
    public long readLong() {
        return (buf[readerIndex++] & 0xFFL)
                | ((buf[readerIndex++] & 0xFFL) << 8)
                | ((buf[readerIndex++] & 0xFFL) << 16)
                | ((buf[readerIndex++] & 0xFFL) << 24)
                | ((buf[readerIndex++] & 0xFFL) << 32)
                | ((buf[readerIndex++] & 0xFFL) << 40)
                | ((buf[readerIndex++] & 0xFFL) << 48)
                | ((buf[readerIndex++] & 0xFFL) << 56);
    }

    @Override
    public String readString() {
        return readString(InPacket.DEFAULT_CHARSET);
    }

    @Override
    public String readString(Charset charset) {
        int length = readUnsignedShort();
        if (length > available()) {
            throw new IllegalStateException("字符串长度越界: length=" + length + ", available=" + available());
        }
        return new String(readBytes(length), charset);
    }

    @Override
    public byte[] readBytes(int count) {
        byte[] out = new byte[count];
        System.arraycopy(buf, readerIndex, out, 0, count);
        readerIndex += count;
        return out;
    }

    @Override
    public void skip(int count) {
        readerIndex += count;
    }

    @Override
    public int available() {
        return buf.length - readerIndex;
    }

    @Override
    public byte[] getBytes() {
        return Arrays.copyOf(buf, buf.length);
    }
}
