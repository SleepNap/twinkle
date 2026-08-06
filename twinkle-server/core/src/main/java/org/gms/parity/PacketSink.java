package org.gms.parity;

/**
 * 回放目标：消费一条客户端原始包负载。
 *
 * <p>录包回放（架构 M0 第 8 项 / M2 parity）：把录制到的客户端包原样投递进服务端。
 * 具体投递实现（走 Netty decoder / 直接调 PacketCodec）由上层提供本接口的适配器。
 */
public interface PacketSink {

    /**
     * 接收一条包负载（原始字节，未加密）。
     */
    void accept(byte[] payload);
}
