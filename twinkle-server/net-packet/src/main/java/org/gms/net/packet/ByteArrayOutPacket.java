package org.gms.net.packet;

import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * 可增长的包写出实现（小端字节序，自研）。
 *
 * <p>内部为可增长 byte[]，写入即推进指针；{@link #getBytes()} 返回当前已写内容副本。
 * 单连接内对象不复用（每包新建），无共享状态。
 */
public final class ByteArrayOutPacket implements OutPacket {

    private byte[] buf;
    private int pos;

    public ByteArrayOutPacket() {
        this.buf = new byte[64];
    }

    @Override
    public OutPacket writeByte(int value) {
        ensure(1);
        buf[pos++] = (byte) value;
        return this;
    }

    @Override
    public OutPacket writeShort(int value) {
        ensure(2);
        buf[pos++] = (byte) value;
        buf[pos++] = (byte) (value >> 8);
        return this;
    }

    @Override
    public OutPacket writeInt(int value) {
        ensure(4);
        buf[pos++] = (byte) value;
        buf[pos++] = (byte) (value >> 8);
        buf[pos++] = (byte) (value >> 16);
        buf[pos++] = (byte) (value >> 24);
        return this;
    }

    @Override
    public OutPacket writeLong(long value) {
        ensure(8);
        buf[pos++] = (byte) value;
        buf[pos++] = (byte) (value >> 8);
        buf[pos++] = (byte) (value >> 16);
        buf[pos++] = (byte) (value >> 24);
        buf[pos++] = (byte) (value >> 32);
        buf[pos++] = (byte) (value >> 40);
        buf[pos++] = (byte) (value >> 48);
        buf[pos++] = (byte) (value >> 56);
        return this;
    }

    @Override
    public OutPacket writeBool(boolean value) {
        return writeByte(value ? 1 : 0);
    }

    @Override
    public OutPacket writeString(String value) {
        return writeString(value, InPacket.DEFAULT_CHARSET);
    }

    @Override
    public OutPacket writeString(String value, Charset charset) {
        byte[] bytes = value.getBytes(charset);
        writeShort(bytes.length);
        return writeBytes(bytes);
    }

    @Override
    public OutPacket writeBytes(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
        return this;
    }

    @Override
    public OutPacket skip(int count) {
        ensure(count);
        Arrays.fill(buf, pos, pos + count, (byte) 0);
        pos += count;
        return this;
    }

    @Override
    public int length() {
        return pos;
    }

    @Override
    public byte[] getBytes() {
        return Arrays.copyOf(buf, pos);
    }

    private void ensure(int extra) {
        if (pos + extra <= buf.length) {
            return;
        }
        int newCapacity = Math.max(buf.length << 1, pos + extra);
        buf = Arrays.copyOf(buf, newCapacity);
    }
}
