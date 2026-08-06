package org.gms.parity;

import org.gms.parity.PacketReplaySupport.PacketRecord;

import java.util.List;

/**
 * 默认回放器（架构 M0 第 8 项：录包回放基建落地）。
 *
 * <p>把录制包按序投递给 {@link PacketSink}（服务端收包入口）。M2 接入 Netty 协议栈后，
 * sink 由网络会话实现。
 */
public final class DefaultPacketReplayer implements PacketReplaySupport.PacketReplayer {

    private final PacketSink sink;

    public DefaultPacketReplayer(PacketSink sink) {
        this.sink = sink;
    }

    @Override
    public void replay(List<PacketRecord> packets) {
        for (PacketRecord packet : packets) {
            sink.accept(packet.payload());
        }
    }
}
