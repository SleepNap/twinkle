package org.gms.net.packet;

import org.gms.diagnostics.PacketTrace;

/**
 * 连接会话最小接口（handler 的对外出口，定义在协议层使 net-packet 零依赖）。
 *
 * <p>IO 层（net-netty）的会话实现本接口。handler 经此发包 / 断链 / 读写连接级状态，
 * 不触碰传输细节（红线 11：可替换层经接口访问稳定层）。
 */
public interface PacketSession {

    /** 协议版本单源（v83 = 83）：握手 hello 明文、加密 header 生成/校验（CipherPair 收发 key）共用。
     * 定义在协议层（net-packet），IO 层（net-netty）与本连接的加解密引用同一常量——避免 hello 里的
     * 版本号与加密用的版本 key 分叉。 */

    short MAPLE_VERSION = 83;

    /**
     * 发送一个包到对端。
     */
    void send(OutPacket packet);

    /**
     * 主动关闭连接并记录原因。
     */
    void close(String reason);

    /**
     * 当前连接阶段（登录协议状态机 {@link SessionStage}），handler 校验包顺序用。
     */
    SessionStage stage();

    /**
     * 推进连接阶段（handler 完成阶段工作后调用）。
     */
    void transition(SessionStage stage);

    /**
     * 读连接级属性（当前账号、已选角色等，handler 之间传递，不进可替换层静态状态）。
     */
    <T> T getAttr(String key);

    /**
     * 写连接级属性。
     */
    void setAttr(String key, Object value);

    /**
     * 本连接的不可变会话 id（创建时生成、全局单调，事故报告阶段 B：会话代际的
     * 第一维）。同一 TCP 连接全程不变；角色被认领时在其之上再叠 {@code sessionGeneration}，
     * 归属用 {@code (characterId, sessionId, sessionGeneration)} 三元组证明。
     */
    long sessionId();

    /** 开启或重置本连接的临时封包监听；不支持监听的会话实现返回 {@code null}。 */
    default PacketTrace.Snapshot startPacketTrace(PacketTrace.Config config) {
        return null;
    }

    /** 读取监听窗口；afterSequence=0 返回最新一页，正数则返回其后的增量。 */
    default PacketTrace.Snapshot packetTraceSnapshot(long afterSequence, int limit) {
        return null;
    }

    /** 停止采集但保留已有窗口，便于 GM 停止后继续查看。 */
    default PacketTrace.Snapshot stopPacketTrace() {
        return null;
    }
}
