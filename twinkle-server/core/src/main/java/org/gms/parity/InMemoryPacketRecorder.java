package org.gms.parity;

import java.util.ArrayList;
import java.util.List;

/**
 * 进程内录制器（架构 M0 第 8 项：parity 录包回放基建落地）。
 *
 * <p>收集收发包原始字节（含时间戳）到内存，可导出快照供回放/比对。
 * M1 落地机制；接入真实客户端录制的时序 M2 接 Netty 协议栈后完成。
 */
public final class InMemoryPacketRecorder implements PacketReplaySupport.PacketRecorder {

    private final List<PacketReplaySupport.PacketRecord> records = new ArrayList<>();

    @Override
    public void record(PacketReplaySupport.PacketRecord packet) {
        records.add(packet);
    }

    /**
     * 已录制的包快照（不可变视图）。
     */
    public List<PacketReplaySupport.PacketRecord> snapshot() {
        return List.copyOf(records);
    }

    public void clear() {
        records.clear();
    }

    public int size() {
        return records.size();
    }
}
