package org.gms.net.packet;

/**
 * 网络包原始字节视图（架构 net-packet：v83 协议字节级最底层）。
 *
 * <p>v83 包负载 = 原始字节序列。读写方向各有扩展接口 {@link InPacket} / {@link OutPacket}，
 * 本接口只承载"取原始字节"这一最小约定，供加密、比对、录包回放等字节级操作使用。
 */
public interface Packet {

    /**
     * 完整负载字节（不含 4 字节 header）。
     */
    byte[] getBytes();
}
