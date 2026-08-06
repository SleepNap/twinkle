package org.gms.parity;

import java.util.List;

/**
 * 录包回放框架接口（架构 M0 第 8 项 / M2 parity 测试基建）。
 *
 * <p>M2 对参考项目（北斗）作 parity 真值时用：录制客户端的收发包序列 → 回放到本服务端 → 断言
 * 行为对齐参考项目。
 *
 * <h2>M0 阶段</h2>
 * 只定接口（{@link PacketReplayer} / {@link PacketRecorder}），不实现。M2 接入 Netty 协议栈后落地：
 * <ul>
 *   <li>{@code net.packet} 提供字节级包结构（v83 opcode 原样）</li>
 *   <li>{@code net.netty} 提供 session 回放入口</li>
 *   <li>本框架负责"录制 → 存文件 → 回放 → 断言"的编排</li>
 * </ul>
 *
 * <p>许可纪律：parity 对照的是「行为对齐」，不是复制参考项目代码（见 CLAUDE.local.md 参考纪律）。
 * 录包回放是独立实现的黑盒测试手段，合规。
 */
public final class PacketReplaySupport {

    private PacketReplaySupport() {
    }

    /**
     * 单条录制的包记录：时间戳 + 原始字节。
     */
    public record PacketRecord(long timestampMillis, byte[] payload) {
    }

    /**
     * 录制器：捕获客户端收发包。
     */
    public interface PacketRecorder {
        void record(PacketRecord packet);
    }

    /**
     * 回放器：按序投递录制包到服务端。
     */
    public interface PacketReplayer {
        void replay(List<PacketRecord> packets);
    }
}
